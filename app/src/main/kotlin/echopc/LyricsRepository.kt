package echopc

import com.music.innertube.models.SongItem
import com.music.kugou.KuGou
import com.music.lrclib.LrcLib
import com.music.simpmusic.SimpMusicLyrics
import com.music.youlyplus.YouLyPlus
import echo.music.iad1tya.betterlyrics.BetterLyrics
import echopc.db.LibraryRepository
import echopc.lyrics.LyricsEntry
import echopc.lyrics.LyricsUtils
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull



private const val LYRICS_NOT_FOUND = "LYRICS_NOT_FOUND"

/**
 * Desktop port of the app's LyricsHelper: asks all providers in parallel and takes the first
 * synced result in priority order, falling back to plain lyrics. The provider modules
 * (lrclib, kugou, betterlyrics, simpmusic, youlyplus) are the app's own code, unchanged.
 * When [store] is set, found lyrics are kept in the library database (the `lyrics` table).
 */
object LyricsRepository {
    private class Provider(val name: String, val fetch: suspend (SongItem) -> Result<String>)

    private fun artists(s: SongItem) = s.artists.joinToString { it.name }
    private fun seconds(s: SongItem) = s.duration ?: -1

    // Same relative order as LyricsProviderRegistry.getDefaultProviderOrder() for the ported ones.
    private val providers = listOf(
        Provider("YouLyPlus") { s -> YouLyPlus.getLyrics(s.title, artists(s), seconds(s), s.album?.name, s.id) },
        Provider("BetterLyrics") { s -> BetterLyrics.getLyrics(s.title, artists(s), seconds(s), s.album?.name) },
        Provider("SimpMusic") { s -> SimpMusicLyrics.getLyrics(s.id, seconds(s)) },
        Provider("LrcLib") { s -> LrcLib.getLyrics(s.title, artists(s), seconds(s), s.album?.name) },
        Provider("Kugou") { s -> KuGou.getLyrics(s.title, artists(s), seconds(s), s.album?.name) },
    )

    @Volatile var store: LibraryRepository? = null

    // Small LRU (the database keeps everything long-term).
    private val cache: MutableMap<String, LyricsUiState> = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, LyricsUiState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsUiState>?) = size > 30
    })
    private const val PROVIDER_TIMEOUT_MS = 10_000L

    private fun fromRaw(provider: String, raw: String): LyricsUiState.Loaded? {
        if (raw.isBlank() || raw == LYRICS_NOT_FOUND) return null
        val parsed = LyricsUtils.parseLyrics(raw)
        if (parsed.isNotEmpty() && raw.trimStart().startsWith("[")) return LyricsUiState.Loaded(provider, parsed, synced = true)
        val plain = raw.lines().filter { it.isNotBlank() }.map { LyricsEntry(-1L, it.trim()) }
        return plain.takeIf { it.isNotEmpty() }?.let { LyricsUiState.Loaded(provider, it, synced = false) }
    }

    suspend fun load(song: SongItem, log: (String) -> Unit = {}): LyricsUiState {
        cache[song.id]?.let { return it }

        // Previously found lyrics are kept in the database, so replays need no network.
        runCatching { store?.cachedLyrics(song.id) }.getOrNull()?.let { saved ->
            fromRaw(saved.provider, saved.lyrics)?.let { state ->
                log("${saved.provider}: from library database")
                cache[song.id] = state
                return state
            }
        }

        var chosenRaw: String? = null
        var chosenProvider = ""
        val state = coroutineScope {
            val pending: List<Pair<Provider, Deferred<String?>>> = providers.map { p ->
                p to async {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                        runCatching { p.fetch(song).getOrNull() }.getOrNull()
                    }
                }
            }
            var bestPlain: LyricsUiState.Loaded? = null
            var bestPlainRaw: String? = null
            var bestPlainProvider = ""
            for ((provider, deferred) in pending) {
                val raw = deferred.await()
                val loaded = raw?.let { fromRaw(provider.name, it) }
                if (loaded == null) { log("${provider.name}: none"); continue }
                if (loaded.synced) {
                    log("${provider.name}: synced, ${loaded.lines.size} lines")
                    pending.forEach { it.second.cancel() }
                    chosenRaw = raw; chosenProvider = provider.name
                    return@coroutineScope loaded
                }
                log("${provider.name}: plain text")
                if (bestPlain == null) { bestPlain = loaded; bestPlainRaw = raw; bestPlainProvider = provider.name }
            }
            if (bestPlain != null) { chosenRaw = bestPlainRaw; chosenProvider = bestPlainProvider }
            bestPlain ?: LyricsUiState.NotFound
        }
        chosenRaw?.let { raw -> runCatching { store?.cacheLyrics(song.id, raw, chosenProvider) } }
        cache[song.id] = state
        return state
    }

    /** Romanized text for a line, or null when the script isn't one we can romanize. */
    suspend fun romanize(text: String): String? = runCatching {
        when {
            LyricsUtils.isJapanese(text) -> LyricsUtils.romanizeJapanese(text)
            LyricsUtils.isKorean(text) -> LyricsUtils.romanizeKorean(text)
            LyricsUtils.isChinese(text) -> LyricsUtils.romanizeChinese(text)
            LyricsUtils.isHindi(text) -> LyricsUtils.romanizeHindi(text)
            LyricsUtils.isPunjabi(text) -> LyricsUtils.romanizePunjabi(text)
            else -> LyricsUtils.romanizeCyrillic(text)
        }?.takeIf { it.isNotBlank() && it != text }
    }.getOrNull()
}
