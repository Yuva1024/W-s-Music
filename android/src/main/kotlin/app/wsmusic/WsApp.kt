package app.wsmusic

import android.app.Application
import androidx.room.Room
import echopc.AppHooks
import echopc.AudioLoader
import echopc.Controller
import echopc.StreamLog
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One loader for the whole app: the player reads its files, the engine asks it to fetch the next song early. */
object Streams {
    val loader = AudioLoader()
}

/**
 * Creates the shared [Controller] (the same app logic as the desktop: queue, search, mixes, feedback) once per
 * process. The screens and the background playback service both use it.
 */
class WsApp : Application() {
    lateinit var controller: Controller
        private set
    lateinit var engine: AndroidEngine
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        StreamLog.sink = { Diagnostics.add(it) }
        AppHooks.log = { Diagnostics.add(it) }
        AppHooks.backgroundDelayMs = 6_000
        AppHooks.dominantColor = { url -> Artwork.dominant(url) }
        AppHooks.updates = AndroidUpdater(this)
        AppHooks.deviceName = { android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Phone" }

        val db = LibraryDatabase.configure(Room.databaseBuilder(this, LibraryDatabase::class.java, getDatabasePath("library.db").absolutePath))
        val library = LibraryRepository(db)
        controller = Controller(scope, library, File(filesDir, "settings.properties")) { onState, onEnded ->
            AndroidEngine(this, onState, onEnded).also { engine = it }
        }
        engine.songLookup = { id -> controller.queue.items.firstOrNull { it.id == id } }
        controller.warmUp()
        controller.startSync()
        scope.launch {
            delay(20_000) // after the first screen and song, not during start-up
            controller.checkForUpdates()
        }
    }

    companion object {
        lateinit var instance: WsApp
            private set
    }
}
