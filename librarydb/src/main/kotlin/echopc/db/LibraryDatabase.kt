package echopc.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import echo.music.iad1tya.db.entities.AlbumArtistMap
import echo.music.iad1tya.db.entities.AlbumEntity
import echo.music.iad1tya.db.entities.ArtistEntity
import echo.music.iad1tya.db.entities.Event
import echo.music.iad1tya.db.entities.FormatEntity
import echo.music.iad1tya.db.entities.LyricsEntity
import echo.music.iad1tya.db.entities.PlayCountEntity
import echo.music.iad1tya.db.entities.PlaylistEntity
import echo.music.iad1tya.db.entities.PlaylistSongMap
import echo.music.iad1tya.db.entities.PlaylistSongMapPreview
import echo.music.iad1tya.db.entities.SearchHistory
import echo.music.iad1tya.db.entities.SongAlbumMap
import echo.music.iad1tya.db.entities.SongArtistMap
import echo.music.iad1tya.db.entities.SongEntity
import echo.music.iad1tya.db.entities.SortedSongAlbumMap
import echo.music.iad1tya.db.entities.SortedSongArtistMap
import java.io.File
import kotlinx.coroutines.Dispatchers

/**
 * Desktop library database. Same table/column definitions as the Android app's `InternalDatabase`
 * for the tables ported so far, but its own schema: the Android file (schema v48, 21 tables) can't
 * be opened directly because Room checks the full schema.
 *
 * v1: library, playlists, history, lyrics. v2: + desktop recommendation tables ([ListenSignal],
 * [Feedback], [GeneratedMix], [RelatedCache], [ShelfStat]); see [MIGRATION_1_2]. v3: + [TastePick] ([MIGRATION_2_3]).
 * v4: + [SyncOp], [SyncKv] for Wi-Fi sync between the user's devices ([MIGRATION_3_4]).
 */
@Database(
    entities = [
        SongEntity::class,
        ArtistEntity::class,
        AlbumEntity::class,
        PlaylistEntity::class,
        SongArtistMap::class,
        SongAlbumMap::class,
        AlbumArtistMap::class,
        PlaylistSongMap::class,
        SearchHistory::class,
        FormatEntity::class,
        LyricsEntity::class,
        Event::class,
        PlayCountEntity::class,
        ListenSignal::class,
        Feedback::class,
        GeneratedMix::class,
        RelatedCache::class,
        ShelfStat::class,
        TastePick::class,
        SyncOp::class,
        SyncKv::class,
    ],
    views = [
        SortedSongArtistMap::class,
        SortedSongAlbumMap::class,
        PlaylistSongMapPreview::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        /** %APPDATA%\WsMusic\library.db (or ~/.wsmusic/library.db off Windows). Moves over the early "EchoMusicPC" test database once. */
        fun defaultFile(): File {
            val appData = System.getenv("APPDATA")
            val dir = if (appData != null) File(appData, "WsMusic") else File(System.getProperty("user.home"), ".wsmusic")
            dir.mkdirs()
            val target = File(dir, "library.db")
            if (!target.exists() && appData != null) {
                val legacy = File(File(appData, "EchoMusicPC"), "library.db")
                if (legacy.isFile) {
                    runCatching {
                        listOf("", "-wal", "-shm").forEach { ext ->
                            File(legacy.path + ext).takeIf { it.isFile }?.copyTo(File(target.path + ext), overwrite = true)
                        }
                    }
                }
            }
            return target
        }

        fun open(file: File = defaultFile()): LibraryDatabase =
            configure(Room.databaseBuilder<LibraryDatabase>(name = file.absolutePath))

        /**
         * Finishes a builder made by the platform: the desktop passes a file path builder (above), the phone app
         * passes Room.databaseBuilder(context, LibraryDatabase::class.java, path). Same driver and migrations on both.
         */
        fun configure(builder: RoomDatabase.Builder<LibraryDatabase>): LibraryDatabase =
            builder
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

        /** Adds the sync tables. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                listOf(
                    "CREATE TABLE IF NOT EXISTS `sync_op` (`id` TEXT NOT NULL, `origin` TEXT NOT NULL, `seq` INTEGER NOT NULL, `category` TEXT NOT NULL, `key` TEXT NOT NULL, `type` TEXT NOT NULL, `at` INTEGER NOT NULL, `data` TEXT NOT NULL, PRIMARY KEY(`id`))",
                    "CREATE INDEX IF NOT EXISTS `index_sync_op_origin_category_seq` ON `sync_op` (`origin`, `category`, `seq`)",
                    "CREATE INDEX IF NOT EXISTS `index_sync_op_key` ON `sync_op` (`key`)",
                    "CREATE TABLE IF NOT EXISTS `sync_kv` (`k` TEXT NOT NULL, `v` TEXT NOT NULL, PRIMARY KEY(`k`))",
                ).forEach { connection.execSQL(it) }
            }
        }

        /** Adds the artists the user picked when asked what they like. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("CREATE TABLE IF NOT EXISTS `taste_pick` (`artistKey` TEXT NOT NULL, `name` TEXT NOT NULL, `thumbnail` TEXT NOT NULL, `pickedAt` INTEGER NOT NULL, PRIMARY KEY(`artistKey`))")
            }
        }

        /** Adds the recommendation tables. SQL copied from Room's generated schema so validation passes. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                listOf(
                    "CREATE TABLE IF NOT EXISTS `listen_signal` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `songId` TEXT NOT NULL, `artistKey` TEXT NOT NULL, `artistName` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `playedMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `startReason` TEXT NOT NULL, `endReason` TEXT NOT NULL, `context` TEXT NOT NULL)",
                    "CREATE INDEX IF NOT EXISTS `index_listen_signal_songId` ON `listen_signal` (`songId`)",
                    "CREATE INDEX IF NOT EXISTS `index_listen_signal_timestamp` ON `listen_signal` (`timestamp`)",
                    "CREATE TABLE IF NOT EXISTS `feedback` (`targetId` TEXT NOT NULL, `kind` TEXT NOT NULL, `context` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`targetId`, `kind`, `context`))",
                    "CREATE TABLE IF NOT EXISTS `generated_mix` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `subtitle` TEXT NOT NULL, `songs` TEXT NOT NULL, `position` INTEGER NOT NULL, `generatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    "CREATE TABLE IF NOT EXISTS `related_cache` (`seedId` TEXT NOT NULL, `songs` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`seedId`))",
                    "CREATE TABLE IF NOT EXISTS `shelf_stat` (`shelfKey` TEXT NOT NULL, `impressions` INTEGER NOT NULL, `clicks` INTEGER NOT NULL, PRIMARY KEY(`shelfKey`))",
                ).forEach { connection.execSQL(it) }
            }
        }
    }
}
