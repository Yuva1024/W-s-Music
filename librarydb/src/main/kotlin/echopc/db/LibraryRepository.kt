package echopc.db

import com.music.innertube.models.Album
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import echo.music.iad1tya.db.entities.AlbumEntity
import echo.music.iad1tya.db.entities.ArtistEntity
import echo.music.iad1tya.db.entities.Event
import echo.music.iad1tya.db.entities.EventWithSong
import echo.music.iad1tya.db.entities.LyricsEntity
import echo.music.iad1tya.db.entities.Playlist
import echo.music.iad1tya.db.entities.PlaylistEntity
import echo.music.iad1tya.db.entities.PlaylistSong
import echo.music.iad1tya.db.entities.PlaylistSongMap
import echo.music.iad1tya.db.entities.SearchHistory
import echo.music.iad1tya.db.entities.Song
import echo.music.iad1tya.db.entities.SongEntity
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Converts a library row back into the model the player/queue uses. */
fun Song.toSongItem(): SongItem =
    SongItem(
        id = song.id,
        title = song.title,
        artists = artists.map { Artist(name = it.name, id = it.id.takeUnless { id -> id.startsWith("LA") }) },
        album = album?.let { Album(name = it.title, id = it.id) } ?: song.albumId?.let { Album(name = song.albumName.orEmpty(), id = it) },
        duration = song.duration.takeIf { it >= 0 },
        thumbnail = song.thumbnailUrl.orEmpty(),
        explicit = song.explicit,
    )

/**
 * Local-only library operations. Unlike the Android entities' `toggleLike()`, nothing here talks
 * to YouTube (no account is signed in on desktop yet), so liking is purely local.
 */
class LibraryRepository(private val db: LibraryDatabase) {
    private val dao = db.dao()

    /** Direct table access for Wi-Fi sync (echopc.sync), which reads and writes below the usual operations. */
    val syncDao: LibraryDao get() = dao

    // Room returns a song's artists in arbitrary order; restore the stored `position` order.
    private suspend fun List<Song>.withArtistOrder(): List<Song> {
        if (isEmpty()) return this
        val position = HashMap<Pair<String, String>, Int>()
        map { it.song.id }.distinct().chunked(500).forEach { ids ->
            dao.artistMaps(ids).forEach { position[it.songId to it.artistId] = it.position }
        }
        return map { s -> s.copy(artists = s.artists.sortedBy { position[s.song.id to it.id] ?: Int.MAX_VALUE }) }
    }

    val likedSongs: Flow<List<Song>> = dao.likedSongs().map { it.withArtistOrder() }
    val likedIds: Flow<List<String>> = dao.likedIds()
    /** Songs saved for offline listening, newest first. */
    val downloadedSongs: Flow<List<Song>> = dao.downloadedSongs().map { it.withArtistOrder() }

    suspend fun setDownloaded(item: SongItem, downloaded: Boolean) {
        save(item)
        dao.setDownloaded(item.id, downloaded, if (downloaded) LocalDateTime.now() else null)
    }
    val librarySongs: Flow<List<Song>> = dao.librarySongs().map { it.withArtistOrder() }
    val playlists: Flow<List<Playlist>> = dao.playlists()
    fun history(limit: Int = 200): Flow<List<EventWithSong>> =
        dao.history(limit).map { events ->
            val ordered = events.map { it.song }.withArtistOrder()
            events.mapIndexed { i, e -> e.copy(song = ordered[i]) }
        }
    fun playlistSongs(playlistId: String): Flow<List<PlaylistSong>> =
        dao.playlistSongs(playlistId).map { rows ->
            val ordered = rows.map { it.song }.withArtistOrder()
            rows.mapIndexed { i, row -> row.copy(song = ordered[i]) }
        }
    fun playlist(playlistId: String): Flow<Playlist?> = dao.playlist(playlistId)
    fun searchHistory(limit: Int = 12): Flow<List<SearchHistory>> = dao.searchHistory(limit)

    /** Makes sure [item] exists in the database (with artists and album). */
    suspend fun save(item: SongItem): SongEntity {
        dao.getSong(item.id)?.let { return it }
        val now = LocalDateTime.now()
        val entity = SongEntity(
            id = item.id,
            title = item.title,
            duration = item.duration ?: -1,
            thumbnailUrl = item.thumbnail,
            albumId = item.album?.id,
            albumName = item.album?.name,
            explicit = item.explicit,
            date = now,
            dateModified = now,
        )
        val artists = item.artists.map { ArtistEntity(id = it.id ?: ArtistEntity.generateArtistId(), name = it.name) }
        val album = item.album?.let { AlbumEntity(id = it.id, title = it.name, songCount = 1, duration = item.duration ?: 0) }
        dao.upsertSongGraph(entity, artists, album)
        return dao.getSong(item.id) ?: entity
    }

    suspend fun toggleLike(item: SongItem) {
        val song = save(item)
        val now = LocalDateTime.now()
        dao.update(
            song.copy(
                liked = !song.liked,
                likedDate = if (!song.liked) now else null,
                inLibrary = if (!song.liked) song.inLibrary ?: now else song.inLibrary,
            ),
        )
    }

    /** Marks the song as liked (does nothing if it already is; [toggleLike] would un-like it). */
    suspend fun like(item: SongItem) {
        val song = save(item)
        if (song.liked) return
        val now = LocalDateTime.now()
        dao.update(song.copy(liked = true, likedDate = now, inLibrary = song.inLibrary ?: now))
    }

    suspend fun addToLibrary(item: SongItem) {
        val song = save(item)
        if (song.inLibrary == null) dao.update(song.copy(inLibrary = LocalDateTime.now()))
    }

    suspend fun removeFromLibrary(songId: String) = dao.removeFromLibrary(songId)

    // ---- playlists ----

    suspend fun createPlaylist(name: String): PlaylistEntity {
        val playlist = PlaylistEntity(name = name.trim().ifBlank { "New playlist" }, bookmarkedAt = LocalDateTime.now(), isLocal = true)
        dao.insert(playlist)
        return playlist
    }

    suspend fun renamePlaylist(id: String, name: String) = dao.renamePlaylist(id, name.trim(), LocalDateTime.now())
    suspend fun deletePlaylist(id: String) = dao.deletePlaylist(id)

    /** @return false if the song was already in the playlist. */
    suspend fun addToPlaylist(playlistId: String, item: SongItem): Boolean {
        save(item)
        if (dao.countInPlaylist(playlistId, item.id) > 0) return false
        dao.insert(PlaylistSongMap(playlistId = playlistId, songId = item.id, position = dao.nextPosition(playlistId)))
        return true
    }

    suspend fun removeFromPlaylist(map: PlaylistSongMap) = dao.removeFromPlaylist(map.id)

    // ---- history ----

    suspend fun recordPlay(item: SongItem, listenedMs: Long) {
        save(item)
        dao.insert(Event(songId = item.id, timestamp = LocalDateTime.now(), playTime = listenedMs))
        dao.addPlayTime(item.id, listenedMs)
    }

    suspend fun clearHistory() = dao.clearHistory()

    // ---- search history ----

    suspend fun rememberSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        dao.deleteSearch(q) // move an existing entry to the top
        dao.insert(SearchHistory(query = q))
    }

    suspend fun clearSearchHistory() = dao.clearSearchHistory()

    // ---- recommendation signals & feedback ----

    /** Records one play (any length) for the taste profile; the song's metadata is saved too. */
    suspend fun recordSignal(item: SongItem, playedMs: Long, durationMs: Long, startReason: String, endReason: String, context: String, at: LocalDateTime = LocalDateTime.now()) {
        save(item)
        dao.insert(
            ListenSignal(
                songId = item.id, artistKey = item.artistKey(), artistName = item.artists.firstOrNull()?.name.orEmpty(),
                timestamp = at, playedMs = playedMs, durationMs = durationMs,
                startReason = startReason, endReason = endReason, context = context,
            ),
        )
    }

    /** Adds a taste signal for every history entry that has none yet (imported or pre-signal plays). */
    suspend fun backfillSignalsFromHistory() = dao.backfillSignalsFromHistory()

    suspend fun signalsSince(since: LocalDateTime): List<ListenSignal> = dao.signalsSince(since)
    suspend fun countSignalsAfter(since: LocalDateTime): Int = dao.countSignalsAfter(since)

    /** Songs stored in the library database (played, liked, playlists) as player models, by id. */
    suspend fun songsById(ids: Collection<String>): Map<String, SongItem> {
        if (ids.isEmpty()) return emptyMap()
        return ids.distinct().chunked(500).flatMap { dao.songs(it).withArtistOrder() }.associate { it.song.id to it.toSongItem() }
    }

    suspend fun songIdsInPlaylists(): Set<String> = dao.songIdsInPlaylists().toSet()

    val feedback: Flow<List<Feedback>> = dao.feedback()
    suspend fun feedbackNow(): List<Feedback> = dao.feedbackNow()
    suspend fun addFeedback(targetId: String, kind: String, context: String = "") = dao.insert(Feedback(targetId, kind, context, LocalDateTime.now()))
    suspend fun removeFeedback(targetId: String, kind: String, context: String = "") = dao.deleteFeedback(targetId, kind, context)

    // ---- taste picks ----

    val tastePicks: Flow<List<TastePick>> = dao.tastePicks()
    suspend fun tastePicksNow(): List<TastePick> = dao.tastePicksNow()

    /** Replaces the user's picked artists (an empty list clears them). */
    suspend fun replaceTastePicks(picks: List<TastePick>) = dao.replaceTastePicks(picks)

    // ---- generated mixes & caches ----

    val mixes: Flow<List<GeneratedMix>> = dao.mixes()
    suspend fun mixesNow(): List<GeneratedMix> = dao.mixesNow()
    fun mix(id: String): Flow<GeneratedMix?> = dao.mix(id)

    /** Replaces all mixes of [kind] with [mixes] (in one go, so Home never shows a half-written set). */
    suspend fun replaceMixes(kind: String, mixes: List<GeneratedMix>) {
        mixes.forEach { dao.upsert(it) }
        dao.deleteMixesExcept(kind, mixes.map { it.id }.ifEmpty { listOf("") })
    }

    suspend fun cachedRelated(seedId: String, maxAgeDays: Long): List<SongItem>? =
        dao.related(seedId)?.takeIf { it.fetchedAt.isAfter(LocalDateTime.now().minusDays(maxAgeDays)) }?.let { TrackCodec.decode(it.songs) }

    suspend fun cacheRelated(seedId: String, songs: List<SongItem>) = dao.upsert(RelatedCache(seedId, TrackCodec.encode(songs), LocalDateTime.now()))
    suspend fun pruneRelated(olderThanDays: Long) = dao.pruneRelated(LocalDateTime.now().minusDays(olderThanDays))

    suspend fun shelfStats(): Map<String, ShelfStat> = dao.shelfStats().associateBy { it.shelfKey }
    suspend fun recordShelfImpressions(keys: List<String>) {
        if (keys.isEmpty()) return
        keys.forEach { dao.ensureShelf(it) }
        dao.addImpressions(keys)
    }
    suspend fun recordShelfClick(key: String) { dao.ensureShelf(key); dao.addClick(key) }

    // ---- lyrics cache ----

    suspend fun cachedLyrics(id: String): LyricsEntity? = dao.lyrics(id)
    suspend fun cacheLyrics(id: String, lyrics: String, provider: String) = dao.insert(LyricsEntity(id = id, lyrics = lyrics, provider = provider))

    /** Copies likes, library songs, playlists and history from an Android app backup / song.db. */
    suspend fun importAndroid(file: java.io.File): ImportResult = AndroidImporter(db).import(file)

    fun close() = db.close()
}
