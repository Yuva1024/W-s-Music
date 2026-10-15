package echopc

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.awt.Dimension
import java.awt.FileDialog
import java.io.File

const val APP_NAME = "W's MUSIC"

/** Startup timing, written to %APPDATA%\WsMusic\startup.log when the WSMUSIC_TRACE environment variable is set. */
object Trace {
    private val enabled = System.getenv("WSMUSIC_TRACE") != null
    private val jvmStart = java.lang.management.ManagementFactory.getRuntimeMXBean().startTime
    fun mark(label: String) {
        if (!enabled) return
        runCatching {
            val f = File(settingsFile().parentFile.also { it.mkdirs() }, "startup.log")
            f.appendText("%6d ms  %s%n".format(System.currentTimeMillis() - jvmStart, label))
        }
    }
}

fun settingsFile(): File {
    val appData = System.getenv("APPDATA")
    val dir = if (appData != null) File(appData, "WsMusic") else File(System.getProperty("user.home"), ".wsmusic")
    return File(dir, "settings.properties")
}

fun main() {
    Device.configure() // render API + audio engine must be chosen before any UI class loads
    installDesktopHooks()
    runApp()
}

private fun runApp() = application {
    Trace.mark("main entered")
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default) }
    // WSMUSIC_BENCH: benchmark mode (temporary database, settings not saved, muted autoplay of one search result).
    val bench = remember { System.getenv("WSMUSIC_BENCH") != null }
    val library = remember {
        val file = if (bench) File.createTempFile("wsmusic-bench", ".db").also { it.delete(); it.deleteOnExit() } else LibraryDatabase.defaultFile()
        LibraryRepository(LibraryDatabase.open(file)).also { Trace.mark("database opened") }
    }
    val controller = remember {
        LyricsRepository.store = library
        Controller(scope, library, if (bench) null else settingsFile()) { onState, onEnded -> AdaptivePlayer(onState, onEnded) }.also { Trace.mark("controller created") }
    }
    val shortcuts = remember { ShortcutHandler(controller) }
    val icon = remember { BitmapPainter(renderAppIcon(256).toComposeImageBitmap()) }
    // Fit the window to the usable screen (minus taskbar) so small laptop displays are not overrun.
    val usable = remember { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    val startW = minOf(1340, usable.width - 40)
    val startH = minOf(840, usable.height - 40)

    val closeApp: () -> Unit = {
        controller.shutdown()
        // Flow collectors keep querying; stop them before the database closes.
        val job = scope.coroutineContext[kotlinx.coroutines.Job]
        kotlinx.coroutines.runBlocking { job?.cancel(); job?.join() }
        library.close()
        exitApplication()
    }
    controller.requestExit = closeApp

    Window(
        onCloseRequest = closeApp,
        title = APP_NAME,
        icon = icon,
        state = rememberWindowState(size = DpSize(startW.dp, startH.dp), position = androidx.compose.ui.window.WindowPosition.Aligned(androidx.compose.ui.Alignment.Center)),
        onPreviewKeyEvent = shortcuts::onKeyEvent, // see Shortcuts.kt
    ) {
        Trace.mark("window content started")
        window.minimumSize = Dimension(minOf(960, startW), minOf(560, startH))
        LaunchedEffect(Unit) {
            Trace.mark("window composed")
            applyDarkTitleBar(window)
            Trace.mark("title bar styled")
            controller.warmUp()
            kotlinx.coroutines.delay(5_000)
            Device.launchSucceeded() // window survived the first seconds: the start counts as good
            if (!bench && System.getenv("WSMUSIC_NOUPDATE") == null) { kotlinx.coroutines.delay(10_000); controller.checkForUpdates() }
            if (bench) {
                controller.changeVolume(0f)
                controller.search("daft punk get lucky")
                while (controller.results.isEmpty()) kotlinx.coroutines.delay(500)
                controller.playFromList(controller.results, 0)
                Trace.mark("bench: playback requested")
            }
        }
        WsTheme(controller.accent) {
            AppContent(controller, onPickAndroidFile = {
                val dialog = FileDialog(window, "Choose your Android backup (.backup) or song.db", FileDialog.LOAD)
                dialog.isVisible = true
                val name = dialog.file
                val dir = dialog.directory
                if (name != null && dir != null) controller.importAndroid(File(dir, name))
            })
        }
    }
}
