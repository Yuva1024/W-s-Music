package echopc

import androidx.compose.ui.graphics.Color
import com.music.innertube.models.SongItem
import java.nio.file.Path

/** A newer release found on GitHub. */
data class UpdateInfo(
    val version: String,
    val tag: String,
    val repo: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** Hex SHA-256 published by GitHub for the file, when available; the download is refused if it does not match. */
    val sha256: String?,
    val notes: String,
)

/** Finds, downloads and starts a newer version of the app (desktop: installer .exe; phone: APK). */
interface UpdateService {
    fun currentVersion(): String
    fun check(): UpdateInfo?
    fun download(info: UpdateInfo, onProgress: (Float) -> Unit): Path
    fun launchInstaller(file: Path)
    /** True when [launchInstaller] installs without any window and reopens the app by itself. */
    fun installsSilently(info: UpdateInfo): Boolean = false
    /** After the app restarts from an update: a sentence saying whether it worked, or null. */
    fun lastUpdateResult(): String? = null
}

/**
 * What each app (desktop, phone) plugs into the shared [Controller]. Defaults do nothing, so tests and
 * a bare Controller still work.
 */
object AppHooks {
    /** Writes a line to the app's log. */
    @Volatile var log: (String) -> Unit = {}
    /** Delay before background work (mix building) after start-up. */
    @Volatile var backgroundDelayMs: Long = 4_000
    /** Dominant colour of a picture, for the accent behind the player; null when unknown. */
    @Volatile var dominantColor: suspend (url: String) -> Color? = { null }
    /** Lyrics for a song. */
    @Volatile var loadLyrics: suspend (SongItem) -> LyricsUiState = { LyricsUiState.NotFound }
    /** Self-update; null where there is none. */
    @Volatile var updates: UpdateService? = null
    /** This device's name as other devices see it when syncing ("Kiran's PC", "Redmi Note 8"). */
    @Volatile var deviceName: () -> String = { "W's MUSIC" }
    /**
     * Starts the Wi-Fi sync listener (HTTP + discovery) for the given handler; the computer has one, the phone
     * doesn't (it connects to the computer). Closing the result stops listening.
     */
    /** Folder for songs saved for offline listening; null where downloads aren't available. */
    @Volatile var downloadsDir: java.io.File? = null
    /**
     * Starts watching how loud voice chat (Discord) is; the callback gets its peak level (0..1) about 20 times a
     * second. Closing the result stops it. Null where it isn't possible (the phone).
     */
    @Volatile var voiceMeter: ((onLevel: (Float) -> Unit) -> AutoCloseable)? = null
    /** Finds music files on this device (the phone's music library, the computer's music folders). */
    @Volatile var localMusic: LocalMusicSource? = null
    @Volatile var syncServer: ((echopc.sync.SyncManager.SyncHost) -> AutoCloseable)? = null
}

/** Music files on this device, as songs whose ids are "local:<path>" (see OfflineFiles). */
interface LocalMusicSource {
    /** Everything found, sorted by title. Throws [SecurityException] when the app may not read music yet. */
    suspend fun scan(): List<SongItem>
}
