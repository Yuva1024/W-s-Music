package echopc.reco

import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.SongItem
import echopc.db.LibraryRepository
import echopc.fetchRadio
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * Where candidate songs come from. Spotify mines billions of playlists for "songs that appear
 * together"; here the equivalent signal is YouTube Music's radio for a song (its own
 * collaborative "plays well together" list), which is combined across many of the user's seeds.
 */
interface CandidateSource {
    /** Songs related to [seed], most related first. */
    suspend fun related(seed: SongItem): List<SongItem>

    /** Recent tracks by an artist (newest releases first). */
    suspend fun recentReleases(artistId: String): List<SongItem>

    /** An artist's best-known songs (what their page lists first), most popular first. */
    suspend fun topSongs(artistId: String): List<SongItem> = emptyList()
}

/** YouTube Music candidates, cached in the database so rebuilding mixes doesn't refetch. */
class YouTubeCandidates(private val library: LibraryRepository) : CandidateSource {
    override suspend fun related(seed: SongItem): List<SongItem> {
        library.cachedRelated(seed.id, maxAgeDays = 7)?.let { return it }
        polite()
        val radio = runCatching { fetchRadio(seed) }.getOrDefault(emptyList()).take(50)
        if (radio.isNotEmpty()) library.cacheRelated(seed.id, radio)
        return radio
    }

    /**
     * YouTube only gives a release *year*, so "recent" means this year's (and late last year's)
     * albums and singles, newest first, three tracks from each.
     */
    override suspend fun recentReleases(artistId: String): List<SongItem> {
        val key = "releases:$artistId"
        library.cachedRelated(key, maxAgeDays = 3)?.let { return it }
        polite()
        val page = YouTube.artist(artistId).getOrNull() ?: return emptyList()
        val year = LocalDate.now().year
        val minYear = if (LocalDate.now().monthValue <= 2) year - 1 else year
        val releases = page.sections.flatMap { it.items }.filterIsInstance<AlbumItem>()
            .filter { (it.year ?: 0) >= minYear }.distinctBy { it.browseId }
            .sortedByDescending { it.year ?: 0 }.take(3)
        val songs = releases.flatMap { album ->
            polite()
            YouTube.album(album.browseId).getOrNull()?.songs.orEmpty().take(3).map { s ->
                if (s.thumbnail.isBlank()) s.copy(thumbnail = album.thumbnail) else s
            }
        }
        library.cacheRelated(key, songs)
        return songs
    }

    /** The "Top songs" shelf of the artist's page, cached for a month (it barely changes). */
    override suspend fun topSongs(artistId: String): List<SongItem> {
        val key = "picktop:$artistId"
        library.cachedRelated(key, maxAgeDays = 30)?.let { return it }
        polite()
        val page = YouTube.artist(artistId).getOrNull() ?: return emptyList()
        val songs = page.sections.firstOrNull { s -> s.items.any { it is SongItem } }?.items?.filterIsInstance<SongItem>().orEmpty().take(5)
        if (songs.isNotEmpty()) library.cacheRelated(key, songs) // a failed fetch must not stick
        return songs
    }

    /** Spread out network calls a little: mixes rebuild in the background, there is no hurry. */
    private suspend fun polite() = delay(250)
}
