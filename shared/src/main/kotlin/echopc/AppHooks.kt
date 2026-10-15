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
}
