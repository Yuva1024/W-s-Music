package echopc.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import echo.music.iad1tya.db.entities.AlbumArtistMap
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
import echo.music.iad1tya.db.entities.SongAlbumMap
import echo.music.iad1tya.db.entities.SongArtistMap
import echo.music.iad1tya.db.entities.SongEntity
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow

/**
 * Desktop subset of the Android app's `DatabaseDao`. Queries are the app's own SQL (same table
 * and column names); only the blocking functions became `suspend`, as Room needs outside Android.
 */
@Dao
abstract class LibraryDao {
    // ---- songs ----

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY inLibrary DESC")
    abstract fun librarySongs(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY likedDate DESC")
    abstract fun likedSongs(): Flow<List<Song>>

    @Query("SELECT id FROM song WHERE liked")
    abstract fun likedIds(): Flow<List<String>>

    @Query("SELECT * FROM song WHERE id = :id")
    abstract suspend fun getSong(id: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(song: SongEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(artist: ArtistEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(album: AlbumEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(map: SongArtistMap)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(map: SongAlbumMap)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(map: AlbumArtistMap)

    @Update
    abstract suspend fun update(song: SongEntity)

    /** Inserts a song with its artists/album if missing (existing rows are left untouched). */
    @Transaction
    open suspend fun upsertSongGraph(song: SongEntity, artists: List<ArtistEntity>, album: AlbumEntity?) {
        insertIgnore(song)
        artists.forEachIndexed { i, artist ->
            insertIgnore(artist)
            insert(SongArtistMap(songId = song.id, artistId = artist.id, position = i))
        }
        if (album != null) {
            insertIgnore(album)
            insert(SongAlbumMap(songId = song.id, albumId = album.id, index = 0))
        }
    }

    @Query("SELECT * FROM song_artist_map WHERE songId IN (:songIds)")
    abstract suspend fun artistMaps(songIds: List<String>): List<SongArtistMap>

    @Query("UPDATE song SET inLibrary = NULL, liked = 0, likedDate = NULL WHERE id = :id")
    abstract suspend fun removeFromLibrary(id: String)

    @Query("UPDATE song SET totalPlayTime = totalPlayTime + :ms WHERE id = :id")
    abstract suspend fun addPlayTime(id: String, ms: Long)

    // ---- play history ----

    @Insert
    abstract suspend fun insert(event: Event): Long

    @Transaction
    @Query("SELECT * FROM event ORDER BY timestamp DESC LIMIT :limit")
    abstract fun history(limit: Int): Flow<List<EventWithSong>>

    @Query("DELETE FROM event")
    abstract suspend fun clearHistory()

    // ---- playlists ----

    @Transaction
    @Query(
        "SELECT *, (SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = playlist.id) AS songCount FROM playlist WHERE bookmarkedAt IS NOT NULL ORDER BY isPinned DESC, createdAt DESC",
    )
    abstract fun playlists(): Flow<List<Playlist>>

    @Transaction
    @Query(
        "SELECT *, (SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = playlist.id) AS songCount FROM playlist WHERE id = :playlistId",
    )
    abstract fun playlist(playlistId: String): Flow<Playlist?>

    @Insert
    abstract suspend fun insert(playlist: PlaylistEntity)

    @Update
    abstract suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlist WHERE id = :id")
    abstract suspend fun deletePlaylist(id: String)

    @Query("UPDATE playlist SET name = :name, lastUpdateTime = :time WHERE id = :id")
    abstract suspend fun renamePlaylist(id: String, name: String, time: LocalDateTime)

    @Transaction
    @Query("SELECT * FROM playlist_song_map WHERE playlistId = :playlistId ORDER BY position")
    abstract fun playlistSongs(playlistId: String): Flow<List<PlaylistSong>>

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_song_map WHERE playlistId = :playlistId")
    abstract suspend fun nextPosition(playlistId: String): Int

    @Query("SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = :playlistId AND songId = :songId")
    abstract suspend fun countInPlaylist(playlistId: String, songId: String): Int

    @Insert
    abstract suspend fun insert(map: PlaylistSongMap)

    @Query("DELETE FROM playlist_song_map WHERE id = :mapId")
    abstract suspend fun removeFromPlaylist(mapId: Int)

    // ---- search history ----

    @Query("SELECT * FROM search_history ORDER BY id DESC LIMIT :limit")
    abstract fun searchHistory(limit: Int): Flow<List<SearchHistory>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(history: SearchHistory): Long

    @Query("DELETE FROM search_history WHERE query = :query")
    abstract suspend fun deleteSearch(query: String)

    @Query("DELETE FROM search_history")
    abstract suspend fun clearSearchHistory()

    // ---- recommendation engine (schema v2) ----

    @Insert
    abstract suspend fun insert(signal: ListenSignal): Long

    @Query("SELECT * FROM listen_signal WHERE timestamp >= :since ORDER BY timestamp, id")
    abstract suspend fun signalsSince(since: LocalDateTime): List<ListenSignal>

    /**
     * Turns history rows (`event`: imported from the Android app, or logged by older versions) into taste
     * signals. A row already represented by a live signal for the same song within a minute is skipped, so
     * this is safe to run repeatedly. Plays have no skip information here, so they are "import" plays.
     */
    @Query(
        """
        INSERT INTO listen_signal (songId, artistKey, artistName, timestamp, playedMs, durationMs, startReason, endReason, context)
        SELECT e.songId,
          COALESCE((SELECT CASE WHEN a.id = '' OR substr(a.id, 1, 2) = 'LA' THEN 'name:' || lower(trim(a.name)) ELSE a.id END
                    FROM song_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.songId = e.songId ORDER BY m.position LIMIT 1), 'name:unknown'),
          COALESCE((SELECT a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.songId = e.songId ORDER BY m.position LIMIT 1), ''),
          e.timestamp, e.playTime, CASE WHEN s.duration > 0 THEN s.duration * 1000 ELSE 0 END, 'import', 'import', 'history'
        FROM event e JOIN song s ON s.id = e.songId
        WHERE NOT EXISTS (SELECT 1 FROM listen_signal l WHERE l.songId = e.songId AND l.timestamp BETWEEN e.timestamp - 60000 AND e.timestamp + 60000)
        """,
    )
    abstract suspend fun backfillSignalsFromHistory()

    @Query("SELECT COUNT(*) FROM listen_signal WHERE timestamp > :since")
    abstract suspend fun countSignalsAfter(since: LocalDateTime): Int

    @Transaction
    @Query("SELECT * FROM song WHERE id IN (:ids)")
    abstract suspend fun songs(ids: List<String>): List<Song>

    @Query("SELECT DISTINCT songId FROM playlist_song_map")
    abstract suspend fun songIdsInPlaylists(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(feedback: Feedback)

    @Query("DELETE FROM feedback WHERE targetId = :targetId AND kind = :kind AND context = :context")
    abstract suspend fun deleteFeedback(targetId: String, kind: String, context: String)

    @Query("SELECT * FROM feedback")
    abstract fun feedback(): Flow<List<Feedback>>

    @Query("SELECT * FROM feedback")
    abstract suspend fun feedbackNow(): List<Feedback>

    @Query("SELECT * FROM generated_mix ORDER BY position")
    abstract fun mixes(): Flow<List<GeneratedMix>>

    @Query("SELECT * FROM generated_mix ORDER BY position")
    abstract suspend fun mixesNow(): List<GeneratedMix>

    @Query("SELECT * FROM generated_mix WHERE id = :id")
    abstract fun mix(id: String): Flow<GeneratedMix?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(mix: GeneratedMix)

    @Query("DELETE FROM generated_mix WHERE kind = :kind AND id NOT IN (:keep)")
    abstract suspend fun deleteMixesExcept(kind: String, keep: List<String>)

    @Query("SELECT * FROM related_cache WHERE seedId = :seedId")
    abstract suspend fun related(seedId: String): RelatedCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(related: RelatedCache)

    @Query("DELETE FROM related_cache WHERE fetchedAt < :before")
    abstract suspend fun pruneRelated(before: LocalDateTime)

    @Query("SELECT * FROM shelf_stat")
    abstract suspend fun shelfStats(): List<ShelfStat>

    @Query("INSERT OR IGNORE INTO shelf_stat (shelfKey, impressions, clicks) VALUES (:key, 0, 0)")
    abstract suspend fun ensureShelf(key: String)

    @Query("UPDATE shelf_stat SET impressions = impressions + 1 WHERE shelfKey IN (:keys)")
    abstract suspend fun addImpressions(keys: List<String>)

    @Query("UPDATE shelf_stat SET clicks = clicks + 1 WHERE shelfKey = :key")
    abstract suspend fun addClick(key: String)

    // ---- taste picks ----

    @Query("SELECT * FROM taste_pick ORDER BY pickedAt, artistKey")
    abstract fun tastePicks(): Flow<List<TastePick>>

    @Query("SELECT * FROM taste_pick ORDER BY pickedAt, artistKey")
    abstract suspend fun tastePicksNow(): List<TastePick>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(picks: List<TastePick>)

    @Query("DELETE FROM taste_pick")
    abstract suspend fun clearTastePicks()

    @Transaction
    open suspend fun replaceTastePicks(picks: List<TastePick>) {
        clearTastePicks()
        insertAll(picks)
    }

    // ---- sync between the user's devices (schema v4) ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(op: SyncOp): Long

    @Query("SELECT origin, category, MAX(seq) AS seq FROM sync_op GROUP BY origin, category")
    abstract suspend fun syncHave(): List<SyncHave>

    @Query("SELECT * FROM sync_op WHERE origin = :origin AND category = :category AND seq > :after ORDER BY seq LIMIT :limit")
    abstract suspend fun syncOpsAfter(origin: String, category: String, after: Long, limit: Int): List<SyncOp>

    /** The winning (newest) change of every key of [type]. */
    @Query("SELECT * FROM sync_op o WHERE type = :type AND NOT EXISTS (SELECT 1 FROM sync_op p WHERE p.`key` = o.`key` AND (p.at > o.at OR (p.at = o.at AND p.id > o.id)))")
    abstract suspend fun syncLatest(type: String): List<SyncOp>

    @Query("SELECT * FROM sync_op WHERE `key` = :key ORDER BY at DESC, id DESC LIMIT 1")
    abstract suspend fun syncLatestFor(key: String): SyncOp?

    @Query("SELECT COUNT(*) FROM sync_op")
    abstract suspend fun syncOpCount(): Long

    @Query("SELECT v FROM sync_kv WHERE k = :k")
    abstract suspend fun syncKv(k: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putSyncKv(kv: SyncKv)

    /** Emits whenever anything that syncs changes (Room re-runs it after writes to these tables). */
    @Query(
        "SELECT (SELECT COUNT(*) FROM song WHERE liked) + (SELECT COUNT(*) FROM playlist) + (SELECT COUNT(*) FROM playlist_song_map)" +
            " + (SELECT COUNT(*) FROM feedback) + (SELECT COUNT(*) FROM taste_pick) + (SELECT COUNT(*) FROM listen_signal) + (SELECT COUNT(*) FROM event)",
    )
    abstract fun syncWatch(): Flow<Long>

    @Query("SELECT * FROM playlist WHERE isLocal AND bookmarkedAt IS NOT NULL")
    abstract suspend fun localPlaylistsNow(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist WHERE id = :id")
    abstract suspend fun playlistNow(id: String): PlaylistEntity?

    @Query("SELECT m.* FROM playlist_song_map m JOIN playlist p ON p.id = m.playlistId WHERE p.isLocal AND p.bookmarkedAt IS NOT NULL ORDER BY m.playlistId, m.position")
    abstract suspend fun localPlaylistSongMaps(): List<PlaylistSongMap>

    @Query("DELETE FROM playlist_song_map WHERE playlistId = :playlistId AND songId = :songId")
    abstract suspend fun removeSongFromPlaylist(playlistId: String, songId: String)

    @Query("SELECT id FROM song WHERE liked")
    abstract suspend fun likedIdsNow(): List<String>

    @Query("SELECT * FROM listen_signal WHERE id > :after ORDER BY id LIMIT :limit")
    abstract suspend fun signalsAfterId(after: Long, limit: Int): List<ListenSignal>

    @Query("SELECT * FROM event WHERE id > :after ORDER BY id LIMIT :limit")
    abstract suspend fun eventsAfterId(after: Long, limit: Int): List<Event>

    @Query("SELECT COUNT(*) FROM listen_signal WHERE songId = :songId AND timestamp = :at")
    abstract suspend fun countSignal(songId: String, at: LocalDateTime): Int

    @Query("SELECT COUNT(*) FROM event WHERE songId = :songId AND timestamp = :at")
    abstract suspend fun countEvent(songId: String, at: LocalDateTime): Int

    // ---- lyrics cache ----

    @Query("SELECT * FROM lyrics WHERE id = :id")
    abstract suspend fun lyrics(id: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(lyrics: LyricsEntity)
}
