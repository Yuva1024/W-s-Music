package echopc

import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import echopc.sync.PhonePairing
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A player that "plays" instantly: enough to see which device plays what. */
private class LoopbackEngine(private val onState: (PlayerState) -> Unit) : PlayerEngine {
    var playing: String? = null
    var paused = false
    var position = 0L
    override fun play(videoId: String, nextVideoId: String?) { playing = videoId; paused = false; position = 0; report() }
    override fun prefetch(videoId: String) {}
    override fun togglePause() { if (playing != null) { paused = !paused; report() } }
    override fun seek(ms: Long) { position = ms; report() }
    override fun setVolume(v: Double) {}
    override fun stop() { playing = null; report() }
    private fun report() = onState(PlayerState(playing = playing != null && !paused, positionMs = position, durationMs = if (playing != null) 200_000 else 0))
}

/** Spotify-Connect-style control between a computer and a phone, each a full Controller with its own library. */
class RemoteControlTest {
    private fun tmpDb() = File.createTempFile("ws-remote", ".db").also { it.delete(); it.deleteOnExit() }
    private suspend fun until(what: String, check: () -> Boolean) = withTimeout(20_000) { while (!check()) delay(25) }.also { println("ok: $what") }
    private fun song(id: String) = SongItem(id = id, title = "Song $id", artists = listOf(Artist("Artist $id", "UC$id")), duration = 200, thumbnail = "")

    @Test
    fun phoneControlsComputerAndPlaybackMovesBetweenThem() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        AppHooks.deviceName = { "Device" }
        try {
            lateinit var pcEngine: LoopbackEngine
            lateinit var phoneEngine: LoopbackEngine
            AppHooks.syncServer = { DesktopSyncServer(it) }
            val pc = Controller(scope, LibraryRepository(LibraryDatabase.open(tmpDb())), null) { st, _ -> LoopbackEngine(st).also { pcEngine = it } }
            AppHooks.syncServer = null
            val phone = Controller(scope, LibraryRepository(LibraryDatabase.open(tmpDb())), null) { st, _ -> LoopbackEngine(st).also { phoneEngine = it } }
            pc.autoplay = false; phone.autoplay = false
            pc.startSync(); phone.startSync()
            delay(300)

            pc.sync.openPairing()
            until("computer listening") { pc.sync.port > 0 }
            phone.sync.pairWith(phone.sync.manualPc("127.0.0.1:${pc.sync.port}")!!)
            until("codes") { phone.sync.pairing is PhonePairing.Confirm && pc.sync.pairRequest != null }
            phone.sync.confirmCode(); pc.sync.allowPair()
            until("connected both ways") { phone.sync.remote.connected && pc.sync.remote.connected }

            // The bug from 1.2.0: the computer played and was paused; a song tapped on the phone must play on the phone.
            pc.playFromList(listOf(song("p")), 0, "test")
            until("computer playing") { pc.playback.playing }
            pc.togglePause()
            until("phone sees the computer paused on that song") { phone.remoteDevice?.let { it.current?.id == "p" && !it.playing } == true }
            phone.playFromList(listOf(song("q")), 0, "test")
            until("a paused computer doesn't take the phone's taps") { phoneEngine.playing == "q" && phone.playback.playing }
            assertFalse(pc.playback.playing)
            phone.togglePause()
            until("phone paused") { !phone.playback.playing }
            until("computer sees the phone paused") { pc.remoteDevice?.let { it.current?.id == "q" && !it.playing } == true }

            // The computer plays; the phone shows it and becomes its remote.
            pc.playFromList(listOf(song("a"), song("b"), song("c")), 0, "test")
            until("phone sees the computer playing") { phone.remoteControlling && phone.nowQueue.current?.id == "a" }
            assertEquals("Device", phone.playingOn)
            assertFalse(phone.playback.playing) // nothing plays on the phone itself

            phone.togglePause()
            until("pause from the phone") { !pc.playback.playing }
            phone.togglePause()
            until("play from the phone") { pc.playback.playing }
            phone.skipNext()
            until("next from the phone") { pcEngine.playing == "b" }
            phone.seek(60_000)
            until("seek from the phone") { pcEngine.position == 60_000L }

            // A song picked on the phone plays on the computer.
            phone.playFromList(listOf(song("x"), song("y")), 1, "search")
            until("picked song plays on the computer") { pcEngine.playing == "y" }
            assertFalse(phone.playback.playing)

            // Handoff to the phone: same song, from where it was; the computer stops.
            phone.playHere()
            until("phone took over") { phoneEngine.playing == "y" && !pc.playback.playing }
            until("computer now shows the phone") { pc.remoteControlling && pc.nowQueue.current?.id == "y" }
            assertFalse(phone.remoteControlling)

            // Now the computer is the remote.
            pc.togglePause()
            until("pause from the computer") { !phone.playback.playing }
            pc.togglePause()
            until("play from the computer") { phone.playback.playing }

            // And back to the computer from the phone's Devices menu.
            phone.playOnOtherDevice()
            until("back on the computer") { pcEngine.playing == "y" && pc.playback.playing && !phone.playback.playing }
            assertTrue(phone.remoteControlling || withTimeoutOrFalse { phone.remoteControlling })
            pc.shutdown()
        } finally {
            scope.cancel()
            AppHooks.syncServer = null
            AppHooks.deviceName = { "W's MUSIC" }
        }
    }

    private suspend fun withTimeoutOrFalse(check: () -> Boolean): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(5_000) { while (!check()) delay(25); true } ?: false
}
