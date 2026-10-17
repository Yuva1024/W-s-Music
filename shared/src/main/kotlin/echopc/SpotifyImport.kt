package echopc

import com.music.innertube.YouTube
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.SongItem
import echopc.db.LibraryRepository
import echopc.db.TastePick
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.isSuccess
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One song as Spotify lists it. [plays] and [lastPlayed] are filled from listening history only. */
data class SpotifyTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String = "",
    val durationSec: Int = 0,
    val plays: Int = 0,
    val lastPlayed: LocalDateTime? = null,
)

/**
 * One thing to bring over. [kind]: "playlist" (becomes a W's MUSIC playlist), "liked" (liked songs),
 * "listened" (songs from the listening history, counted as plays), "album" (counted as plays) or
 * "artists" (followed artists, added to the picked artists).
 */
data class SpotifyCollection(val kind: String, val name: String, val tracks: List<SpotifyTrack>, val artists: List<String> = emptyList())

data class SpotifyExport(val collections: List<SpotifyCollection>, val notes: List<String> = emptyList()) {
    val trackCount: Int get() = collections.sumOf { it.tracks.size }
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonElement?.str(key: String): String? = (this as? JsonObject)?.get(key)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.trim()
private fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

/** Spotify writes several artists as "A, B" (sometimes with a non-breaking space after the comma). */
internal fun splitArtists(text: String): List<String> = text.split(',').map { it.replace(' ', ' ').trim() }.filter { it.isNotEmpty() }

// =====================================================================================
// Spotify links (public playlist and album pages, no login)
// =====================================================================================

object SpotifyLink {
    /** Spotify's public embed page lists at most this many songs. */
    const val EMBED_LIMIT = 100

    data class Target(val kind: String, val id: String)

    private val pattern = Regex("""(?:open\.spotify\.com/(?:intl-[a-z-]+/)?(?:embed/)?|spotify:)(playlist|album|track|artist)[/:]([A-Za-z0-9]{22})""")

    /** The playlist or album in a pasted link ("https://open.spotify.com/playlist/…?si=…" or "spotify:playlist:…"). */
    fun parse(text: String): Target? = pattern.find(text)?.let { Target(it.groupValues[1], it.groupValues[2]) }

    fun looksLikeLink(text: String): Boolean = parse(text) != null || "spotify.link/" in text || "spotify.app.link/" in text

    private val client by lazy { HttpClient(OkHttp) { followRedirects = true } }
    private const val BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    private suspend fun get(url: String): Pair<String, String> {
        val response = client.get(url) { header("User-Agent", BROWSER); header("Accept-Language", "en") }
        if (!response.status.isSuccess()) throw IllegalStateException(
            if (response.status.value == 404) "Spotify couldn't find that playlist. If it's private, make it public in Spotify for a moment, or use your Spotify data file."
            else "Spotify didn't answer (error ${response.status.value}). Try again in a minute.",
        )
        return response.request.url.toString() to response.bodyAsText()
    }

    /** Reads the songs of a public playlist or album. */
    suspend fun fetch(text: String): SpotifyExport {
        var target = parse(text)
        if (target == null) {
            // Short links (spotify.link/…) redirect to the real page.
            val url = Regex("""https?://\S+""").find(text)?.value ?: throw IllegalArgumentException("Paste a Spotify playlist or album link.")
            val (finalUrl, body) = get(url)
            target = parse(finalUrl) ?: parse(body)
        }
        target ?: throw IllegalArgumentException("That doesn't look like a Spotify playlist or album link.")
        if (target.kind == "track") throw IllegalArgumentException("That's a link to one song. Paste a playlist or album link (Share › Copy link to playlist).")
        if (target.kind == "artist") throw IllegalArgumentException("That's an artist link. Paste a playlist or album link, or add the artist with \"Pick artists you like\".")
        val (_, html) = get("https://open.spotify.com/embed/${target.kind}/${target.id}")
        return parseEmbed(html, target.kind)
    }

    /** Reads the song list from an embed page's `__NEXT_DATA__` block. */
    fun parseEmbed(html: String, kind: String): SpotifyExport {
        val data = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("Spotify's page has changed and can't be read. Use your Spotify data file instead.")
        val entity = json.parseToJsonElement(data).obj("props").obj("pageProps").obj("state").obj("data").obj("entity")
            ?: throw IllegalStateException("Spotify didn't show this ${kind}. If it's private, make it public for a moment, or use your Spotify data file.")
        val name = entity.str("name") ?: entity.str("title") ?: "Spotify $kind"
        val albumArtists = entity.arr("artists")?.mapNotNull { it.str("name") }.orEmpty()
        val tracks = entity.arr("trackList").orEmpty().mapNotNull { t ->
            val title = t.str("title").orEmpty()
            if (title.isEmpty() || (t.str("entityType") ?: "track") != "track") return@mapNotNull null
            SpotifyTrack(
                id = t.str("uri") ?: "$title|${t.str("subtitle")}",
                title = title,
                artists = splitArtists(t.str("subtitle").orEmpty()).ifEmpty { albumArtists },
                album = if (kind == "album") name else "",
                durationSec = ((t.jsonObject["duration"]?.jsonPrimitive?.longOrNull ?: 0L) / 1000).toInt(),
            )
        }
        if (tracks.isEmpty()) throw IllegalStateException("No songs found on that Spotify page.")
        val notes = if (tracks.size >= EMBED_LIMIT)
            listOf("Spotify's public page shows the first $EMBED_LIMIT songs only. To bring in a longer playlist completely, use your Spotify data file.")
        else emptyList()
        // Albums become playlists too: the person asked for exactly these songs, together.
        return SpotifyExport(listOf(SpotifyCollection("playlist", name, tracks)), notes)
    }
}

// =====================================================================================
// Spotify data files (Account › Privacy › Download your data), as .zip or single .json
// =====================================================================================

object SpotifyFiles {
    /** Spotify's "Extended streaming history" download has plays only; playlists and likes are in "Account data". */
    const val HISTORY_ONLY_NOTE = "This file has your listening history only. Your playlists and liked songs are in a separate Spotify download: " +
        "on spotify.com go to Account › Privacy settings › Download your data, tick \"Account data\" and request it. " +
        "When Spotify emails that .zip, import it here too. Plays already imported aren't counted again."

    /** Reads a Spotify data download (.zip) or one of its .json files. Also reads the old browser-extension export. */
    fun read(fileName: String, bytes: ByteArray): SpotifyExport {
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val parts = ArrayList<SpotifyExport>()
        if (isZip) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && name.endsWith(".json", ignoreCase = true) && isWanted(name)) {
                        runCatching { parse(zip.readBytes().decodeToString()) }.getOrNull()?.let { parts += it }
                    }
                }
            }
            if (parts.isEmpty()) throw IllegalArgumentException("No playlists or library found in $fileName. Choose the .zip Spotify emailed you (my_spotify_data.zip).")
        } else {
            parts += parse(bytes.decodeToString())
        }
        return merge(parts)
    }

    private fun isWanted(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("playlist") || n == "yourlibrary.json" || n.startsWith("streaminghistory") ||
            n.startsWith("streaming_history_audio") || n.startsWith("wsmusic-spotify-export")
    }

    /** History files come split by year; their plays are added up into one "listened" list. */
    private fun merge(parts: List<SpotifyExport>): SpotifyExport {
        val all = parts.flatMap { it.collections }
        val history = all.filter { it.kind == "listened" }
        val rest = all.filter { it.kind != "listened" }
        val listened = if (history.isEmpty()) emptyList() else listOf(SpotifyCollection("listened", "Listening history", topPlayed(history.flatMap { it.tracks })))
        val notes = parts.flatMap { it.notes }.distinct().toMutableList()
        if (rest.none { it.kind == "playlist" || it.kind == "liked" }) notes += HISTORY_ONLY_NOTE
        val export = SpotifyExport(rest + listened, notes)
        if (export.collections.all { it.tracks.isEmpty() && it.artists.isEmpty() }) throw IllegalArgumentException("This file has no songs in it.")
        return export
    }

    /** The songs played most (30 s or more counts as a play), up to [limit]. */
    private fun topPlayed(plays: List<SpotifyTrack>, limit: Int = 400): List<SpotifyTrack> =
        plays.groupBy { (it.title + "|" + it.artists.firstOrNull().orEmpty()).lowercase() }.values
            .map { same -> same.first().copy(plays = same.sumOf { it.plays }, lastPlayed = same.mapNotNull { it.lastPlayed }.maxOrNull()) }
            .filter { it.plays >= 2 }
            .sortedByDescending { it.plays }.take(limit)

    fun parse(text: String): SpotifyExport {
        val root = runCatching { json.parseToJsonElement(text.trimStart('﻿')) }.getOrNull()
            ?: throw IllegalArgumentException("This file can't be read. Choose the .zip from Spotify or one of the .json files in it.")
        return when {
            root is JsonObject && root.str("format") == "wsmusic-spotify-export" -> parseExtension(root)
            root is JsonObject && root["playlists"] is JsonArray -> parsePlaylists(root)
            root is JsonObject && (root["tracks"] is JsonArray || root["artists"] is JsonArray) -> parseLibrary(root)
            root is JsonArray -> parseHistory(root)
            else -> throw IllegalArgumentException("This isn't a Spotify playlist, library or history file.")
        }
    }

    /** Playlist1.json: {"playlists":[{"name", "items":[{"track":{"trackName","artistName","albumName","trackUri"}}]}]} */
    private fun parsePlaylists(root: JsonObject) = SpotifyExport(
        root.arr("playlists").orEmpty().mapNotNull { p ->
            val tracks = p.arr("items").orEmpty().mapNotNull { item ->
                val t = item.obj("track") ?: return@mapNotNull null
                val title = t.str("trackName").orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                SpotifyTrack(t.str("trackUri") ?: "$title|${t.str("artistName")}", title, splitArtists(t.str("artistName").orEmpty()), t.str("albumName").orEmpty())
            }
            if (tracks.isEmpty()) null else SpotifyCollection("playlist", p.str("name").takeUnless { it.isNullOrEmpty() } ?: "Spotify playlist", tracks)
        },
    )

    /** YourLibrary.json: {"tracks":[{"artist","album","track","uri"}], "artists":[{"name","uri"}]} */
    private fun parseLibrary(root: JsonObject): SpotifyExport {
        val liked = root.arr("tracks").orEmpty().mapNotNull { t ->
            val title = t.str("track").orEmpty()
            if (title.isEmpty()) null else SpotifyTrack(t.str("uri") ?: "$title|${t.str("artist")}", title, splitArtists(t.str("artist").orEmpty()), t.str("album").orEmpty())
        }
        val artists = root.arr("artists").orEmpty().mapNotNull { it.str("name") }.filter { it.isNotEmpty() }
        return SpotifyExport(listOfNotNull(
            SpotifyCollection("liked", "Liked Songs", liked).takeIf { liked.isNotEmpty() },
            SpotifyCollection("artists", "Followed artists", emptyList(), artists).takeIf { artists.isNotEmpty() },
        ))
    }

    /**
     * StreamingHistory_music_N.json: [{"endTime","artistName","trackName","msPlayed"}], or the extended history
     * Streaming_History_Audio_*.json: [{"ts","ms_played","master_metadata_track_name","master_metadata_album_artist_name",…}].
     */
    private fun parseHistory(root: JsonArray): SpotifyExport {
        val plays = root.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val title = (o.str("master_metadata_track_name") ?: o.str("trackName")).orEmpty()
            val artist = (o.str("master_metadata_album_artist_name") ?: o.str("artistName")).orEmpty()
            val ms = (o["ms_played"] ?: o["msPlayed"])?.jsonPrimitive?.longOrNull ?: 0L
            if (title.isEmpty() || ms < 30_000) return@mapNotNull null
            val at = o.str("ts")?.let { runCatching { LocalDateTime.ofInstant(Instant.parse(it), ZoneId.systemDefault()) }.getOrNull() }
                ?: o.str("endTime")?.let { runCatching { LocalDateTime.parse(it.replace(' ', 'T')) }.getOrNull() }
            SpotifyTrack(o.str("spotify_track_uri") ?: "$title|$artist", title, listOf(artist).filter { it.isNotEmpty() },
                o.str("master_metadata_album_album_name").orEmpty(), plays = 1, lastPlayed = at)
        }
        return SpotifyExport(listOf(SpotifyCollection("listened", "Listening history", plays)))
    }

    /** The file written by the (unpublished) browser extension. */
    private fun parseExtension(root: JsonObject) = SpotifyExport(
        root.arr("collections").orEmpty().mapNotNull { c ->
            val tracks = c.arr("tracks").orEmpty().mapNotNull { t ->
                val title = t.str("title").orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                SpotifyTrack(t.str("id") ?: title, title, t.arr("artists").orEmpty().mapNotNull { a -> runCatching { a.jsonPrimitive.contentOrNull?.trim() }.getOrNull() }.filter { it.isNotEmpty() },
                    t.str("album").orEmpty(), (t as JsonObject)["durationSec"]?.jsonPrimitive?.intOrNull ?: 0)
            }
            val artists = c.arr("artists").orEmpty().mapNotNull { it.str("name") }.filter { it.isNotEmpty() }
            SpotifyCollection(c.str("kind") ?: "listened", c.str("name").takeUnless { it.isNullOrEmpty() } ?: "Spotify", tracks, artists)
        },
    )
}

// =====================================================================================
// Matching and importing
// =====================================================================================

/** Finds the right YouTube Music version of a Spotify song. */
object SpotifyMatch {
    /** Lowest score that counts as "this is the song": every word of the title and the main artist found, no unwanted version. */
    const val ACCEPT = 90

    fun score(track: SpotifyTrack, candidate: SongItem): Int {
        val query = track.title + " " + track.artists.firstOrNull().orEmpty()
        var score = SearchRank.score(query, candidate)
        val length = candidate.duration
        if (track.durationSec > 0 && length != null && length > 0) {
            val diff = abs(length - track.durationSec)
            score += when { diff <= 3 -> 40; diff <= 10 -> 10; diff > 25 -> -60; else -> 0 }
        }
        return score
    }

    fun pick(track: SpotifyTrack, candidates: List<SongItem>): SongItem? =
        candidates.map { it to score(track, it) }.maxByOrNull { it.second }?.takeIf { it.second >= ACCEPT }?.first
}

data class SpotifyImportSummary(
    val uniqueTracks: Int,
    val matched: Int,
    val liked: Int,
    val playlists: List<Pair<String, Int>>,
    val listened: Int,
    val artists: Int,
    val notFound: List<String>,
    val skippedOverLimit: Int,
    val notes: List<String> = emptyList(),
) {
    fun text(): String = buildString {
        append("Found $matched of $uniqueTracks songs on YouTube Music.")
        if (playlists.isNotEmpty()) append("\nPlaylists: " + playlists.joinToString { "${it.first} (${it.second})" } + ".")
        if (liked > 0) append("\nLiked songs added: $liked.")
        if (listened > 0) append("\nSongs from your listening history: $listened.")
        if (artists > 0) append("\nFollowed artists added to your picks: $artists.")
        if (matched > 0) append("\nThese songs now shape your taste, mixes and recommendations.")
        if (notFound.isNotEmpty()) append("\nNot found: " + notFound.take(5).joinToString("; ") + if (notFound.size > 5) " and ${notFound.size - 5} more." else ".")
        if (skippedOverLimit > 0) append("\n$skippedOverLimit songs were left out (the import handles up to $MAX_TRACKS at a time).")
        notes.forEach { append("\n").append(it) }
    }

    companion object { const val MAX_TRACKS = 2000 }
}

/** Puts Spotify playlists, likes, plays and followed artists into the local library, which is what taste is built from. */
class SpotifyImporter(
    private val library: LibraryRepository,
    /** Songs for a text query. Replaced in tests. */
    private val searchSongs: suspend (String) -> List<SongItem> = ::searchYouTubeSongs,
    private val searchArtist: suspend (String) -> ArtistItem? = ::searchYouTubeArtist,
    private val parallel: Int = 4,
) {
    suspend fun import(export: SpotifyExport, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): SpotifyImportSummary {
        // Each song is looked up once even if it is in several places. What the person picked by hand goes first.
        val priority = listOf("playlist", "liked", "listened", "album")
        val ordered = export.collections.sortedBy { c -> priority.indexOf(c.kind).let { if (it < 0) priority.size else it } }
        val all = ordered.flatMap { it.tracks }.distinctBy { it.id }
        val unique = all.take(SpotifyImportSummary.MAX_TRACKS)
        var done = 0
        val matches = HashMap<String, SongItem?>()
        progress(0, unique.size)
        coroutineScope {
            val gate = Semaphore(parallel)
            unique.map { track ->
                async {
                    val song = gate.withPermit { findSong(track) }
                    synchronized(matches) { matches[track.id] = song; done++; progress(done, unique.size) }
                }
            }.awaitAll()
        }

        var liked = 0; var listened = 0
        val playlists = ArrayList<Pair<String, Int>>()
        for (c in ordered) {
            val pairs = c.tracks.mapNotNull { t -> matches[t.id]?.let { t to it } }.distinctBy { it.second.id }
            when (c.kind) {
                // Playlist songs count toward taste on their own (TasteProfile weighs each like two full listens).
                "playlist" -> if (pairs.isNotEmpty()) {
                    val id = playlistFor(c.name)
                    pairs.forEach { library.addToPlaylist(id, it.second) }
                    playlists += c.name to pairs.size
                }
                "liked" -> pairs.forEach { library.like(it.second); liked++ }
                else -> pairs.forEach { (t, s) -> // "listened" and "album": counted as finished listens, as often as they were played (up to 5)
                    // Both Spotify downloads have history (Account data a year, Extended streaming history all of it): count a song once.
                    if (library.syncDao.countSpotifySignals(s.id) > 0) return@forEach
                    val ms = (s.duration ?: 180) * 1000L
                    repeat(t.plays.coerceIn(1, 5)) { library.recordSignal(s, ms, ms, "imported", "trackdone", "spotify:${c.kind}", t.lastPlayed ?: LocalDateTime.now()) }
                    listened++
                }
            }
        }
        val artists = addFollowedArtists(ordered.filter { it.kind == "artists" }.flatMap { it.artists }.distinct())

        val notFound = unique.filter { matches[it.id] == null }.map { it.title + (it.artists.firstOrNull()?.let { a -> " - $a" } ?: "") }
        return SpotifyImportSummary(
            uniqueTracks = unique.size, matched = unique.count { matches[it.id] != null }, liked = liked, playlists = playlists,
            listened = listened, artists = artists, notFound = notFound, skippedOverLimit = (all.size - unique.size).coerceAtLeast(0),
            notes = export.notes,
        )
    }

    /** Importing the same playlist again adds the missing songs to it instead of making a copy. */
    private suspend fun playlistFor(name: String): String =
        library.playlists.first().firstOrNull { it.playlist.isLocal && it.playlist.name.equals(name.trim(), ignoreCase = true) }?.playlist?.id
            ?: library.createPlaylist(name).id

    private suspend fun findSong(track: SpotifyTrack): SongItem? {
        val query = (track.title + " " + track.artists.firstOrNull().orEmpty()).trim()
        return runCatching { SpotifyMatch.pick(track, searchSongs(query)) }.getOrNull()
    }

    private suspend fun addFollowedArtists(names: List<String>): Int {
        if (names.isEmpty()) return 0
        val existing = library.tastePicksNow()
        val have = existing.map { it.artistKey }.toSet()
        val now = LocalDateTime.now()
        val found = coroutineScope {
            val gate = Semaphore(parallel)
            names.take(150).map { n -> async { gate.withPermit { runCatching { searchArtist(n) }.getOrNull() } } }.awaitAll()
        }.filterNotNull().distinctBy { it.id }.filter { it.id !in have }
        if (found.isNotEmpty()) library.replaceTastePicks(existing + found.map { TastePick(it.id, it.title, it.thumbnail.orEmpty(), now) })
        return found.size
    }
}

private suspend fun searchYouTubeSongs(query: String): List<SongItem> {
    repeat(2) {
        val result = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
        if (result != null) {
            val songs = result.items.filterIsInstance<SongItem>()
            if (songs.isNotEmpty()) return songs
            // Not a "song" on YouTube Music: it may only exist as a video upload.
            return YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO).getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
        }
        kotlinx.coroutines.delay(400)
    }
    return emptyList()
}

private suspend fun searchYouTubeArtist(name: String): ArtistItem? {
    val wanted = name.trim().lowercase()
    return YouTube.search(name, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()?.items?.filterIsInstance<ArtistItem>()
        ?.firstOrNull { it.title.trim().lowercase() == wanted }
}
