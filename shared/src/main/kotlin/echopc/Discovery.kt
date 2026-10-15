package echopc

import com.music.innertube.YouTube
import com.music.innertube.models.Artist
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import echopc.db.LibraryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** "Popular right now": a YouTube Music hit playlist, with the playlist's name for the label. */
class PopularNow(val title: String, val songs: List<SongItem>)

/**
 * What a brand-new user is shown before there is any taste to go on: popular songs, and artists to
 * pick from. Behind an interface so screens and tests don't need the network.
 */
interface DiscoverySource {
    suspend fun popularNow(): PopularNow?

    /** Well-known artists to start the picker with. */
    suspend fun suggestedArtists(): List<ArtistItem>

    suspend fun searchArtists(query: String): List<ArtistItem>

    /** Artists "fans might also like" for [artistId] (suggested as the user picks). */
    suspend fun similarArtists(artistId: String): List<ArtistItem>
}

/**
 * YouTube Music's own editorial playlists are the source: its charts page is empty for a visitor who is
 * not signed in, but "The Hit List" and the other hit playlists by "YouTube Music" are not. Results are
 * cached in the database (songs for a day, artists for a week) so Home does not refetch them on every launch.
 */
class YouTubeDiscovery(private val library: LibraryRepository) : DiscoverySource {

    private suspend fun hitPlaylists(): List<PlaylistItem> =
        YouTube.search("top hits", YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST).getOrNull()?.items.orEmpty()
            .filterIsInstance<PlaylistItem>().filter { it.author?.name.equals("YouTube Music", ignoreCase = true) }

    override suspend fun popularNow(): PopularNow? {
        library.cachedRelated(POPULAR, maxAgeDays = 1)?.takeIf { it.isNotEmpty() }?.let { songs ->
            val title = library.cachedRelated(POPULAR_TITLE, maxAgeDays = 1)?.firstOrNull()?.title ?: "The Hit List"
            return PopularNow(title, songs)
        }
        val playlists = hitPlaylists()
        val playlist = playlists.firstOrNull { it.title.contains("Hit List", ignoreCase = true) } ?: playlists.firstOrNull()
        val songs = playlist?.let { YouTube.playlist(it.id).getOrNull()?.songs.orEmpty().distinctBy { s -> s.id }.take(30) }.orEmpty()
        if (playlist == null || songs.isEmpty()) return stalePopular() // offline or blocked: last known songs beat an empty Home
        library.cacheRelated(POPULAR, songs)
        library.cacheRelated(POPULAR_TITLE, listOf(SongItem(id = "title", title = playlist.title, artists = emptyList(), thumbnail = "")))
        return PopularNow(playlist.title, songs)
    }

    /** The last copy we saved, however old. Home still looks alive offline; the songs play again once reconnected. */
    private suspend fun stalePopular(): PopularNow? {
        val songs = library.cachedRelated(POPULAR, maxAgeDays = 365)?.takeIf { it.isNotEmpty() } ?: return null
        return PopularNow(library.cachedRelated(POPULAR_TITLE, maxAgeDays = 365)?.firstOrNull()?.title ?: "The Hit List", songs)
    }

    /**
     * The artists most often credited first on YouTube Music's hit playlists (a mix of eras and regions),
     * with their photos looked up by search. A song only carries its artist's name and id, not a picture.
     */
    override suspend fun suggestedArtists(): List<ArtistItem> {
        library.cachedRelated(ARTISTS, maxAgeDays = 7)?.takeIf { it.isNotEmpty() }?.let { return it.map(::fromCache) }
        val counts = LinkedHashMap<String, Pair<Artist, Int>>()
        for (playlist in hitPlaylists().take(4)) {
            for (song in YouTube.playlist(playlist.id).getOrNull()?.songs.orEmpty().take(25)) {
                val a = song.artists.firstOrNull()?.takeIf { it.id != null } ?: continue
                counts.merge(a.id!!, a to 1) { old, _ -> old.first to old.second + 1 }
            }
        }
        val wanted = counts.values.sortedByDescending { it.second }.map { it.first }.take(24)
        if (wanted.isEmpty()) return library.cachedRelated(ARTISTS, maxAgeDays = 365).orEmpty().map(::fromCache) // offline: the saved list
        val gate = Semaphore(6)
        val resolved = coroutineScope {
            wanted.map { a ->
                async {
                    gate.withPermit {
                        val hits = YouTube.search(a.name, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()?.items.orEmpty().filterIsInstance<ArtistItem>()
                        hits.firstOrNull { it.id == a.id } ?: ArtistItem(id = a.id!!, title = a.name, thumbnail = null, shuffleEndpoint = null, radioEndpoint = null)
                    }
                }
            }.awaitAll()
        }
        if (resolved.isNotEmpty()) library.cacheRelated(ARTISTS, resolved.map(::toCache))
        return resolved
    }

    override suspend fun searchArtists(query: String): List<ArtistItem> =
        YouTube.search(query.trim(), YouTube.SearchFilter.FILTER_ARTIST).getOrNull()?.items.orEmpty().filterIsInstance<ArtistItem>().take(18)

    override suspend fun similarArtists(artistId: String): List<ArtistItem> =
        YouTube.artist(artistId).getOrNull()?.sections.orEmpty()
            .lastOrNull { s -> s.items.isNotEmpty() && s.items.all { it is ArtistItem } }?.items.orEmpty().filterIsInstance<ArtistItem>().take(10)

    // Artists are cached in the song cache as one proxy song each (id, name, picture).
    private fun toCache(a: ArtistItem) = SongItem(id = a.id, title = a.title, artists = emptyList(), thumbnail = a.thumbnail.orEmpty())
    private fun fromCache(s: SongItem) = ArtistItem(id = s.id, title = s.title, thumbnail = s.thumbnail.ifBlank { null }, shuffleEndpoint = null, radioEndpoint = null)

    private companion object {
        const val POPULAR = "popular-now"
        const val POPULAR_TITLE = "popular-now:title"
        const val ARTISTS = "picker-artists"
    }
}
