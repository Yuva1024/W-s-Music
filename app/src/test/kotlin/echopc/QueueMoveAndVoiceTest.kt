package echopc

import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** A player that remembers its volume and pause state. */
private class VolumeEngine(private val onState: (PlayerState) -> Unit) : PlayerEngine {
    var level = 1.0
    var paused = false
    var playing: String? = null
    override fun play(videoId: String, nextVideoId: String?) { playing = videoId; paused = false; report() }
    override fun prefetch(videoId: String) {}
    override fun togglePause() { paused = !paused; report() }
    override fun seek(ms: Long) {}
    override fun setVolume(v: Double) { level = v }
    override fun stop() { playing = null }
    private fun report() = onState(PlayerState(playing = playing != null && !paused, durationMs = 200_000))
}

class QueueMoveAndVoiceTest {
    private val songs = (1..5).map { fakeSong(it, "Song $it", "Artist $it") }

    @Test
    fun movingSongsKeepsThePlayingOne() {
        val q = QueueState(songs, index = 1)
        // Drag the last song up to play right after the current one.
        val a = q.move(4, 2)
        assertEquals(listOf(1, 2, 5, 3, 4), a.items.map { it.id.removePrefix("fake").toInt() })
        assertEquals("fake2", a.current?.id)
        // Moving a song from before the current one to after it: the current one moves up a place.
        val b = q.move(0, 3)
        assertEquals("fake2", b.current?.id); assertEquals(0, b.index)
        // And the other way round.
        val c = q.move(4, 0)
        assertEquals("fake2", c.current?.id); assertEquals(2, c.index)
        assertEquals(q, q.move(2, 2))
        assertEquals(q, q.move(9, 1))
    }

    private fun controller(scope: CoroutineScope, engine: (VolumeEngine) -> Unit): Controller =
        Controller(scope, LibraryRepository(LibraryDatabase.open(File.createTempFile("ws-voice", ".db").also { it.delete(); it.deleteOnExit() })), null) { st, _ ->
            VolumeEngine(st).also(engine)
        }

    @Test
    fun discordModeLowersOrPausesOnlyWhileSomeoneTalks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var feed: ((Float) -> Unit)? = null
        var closed = false
        AppHooks.voiceMeter = { onLevel -> feed = onLevel; AutoCloseable { closed = true } }
        try {
            lateinit var engine: VolumeEngine
            val c = controller(scope) { engine = it }
            c.autoplay = false
            c.changeVolume(0.5f)
            c.playFromList(songs, 0, "test")
            c.moveInQueue(4, 1)
            assertEquals("fake5", c.queue.upNext?.id)

            // Lower: down within a moment of someone talking, back up after they stop.
            c.changeVoiceDuck(VoiceDuck.LOWER)
            val talk = feed!!
            repeat(3) { talk(0.3f) }
            assertTrue(c.voiceTalking)
            assertEquals(0.5 * 0.2, engine.level, 0.001)
            assertEquals(0.5f, c.volume) // the person's own volume setting is untouched
            Thread.sleep(900)
            repeat(15) { talk(0f) }
            assertFalse(c.voiceTalking)
            assertEquals(0.5, engine.level, 0.001)

            // Pause: paused while someone talks, playing again after a quiet moment.
            c.changeVoiceDuck(VoiceDuck.PAUSE)
            talk(0.2f)
            assertTrue(engine.paused)
            talk(0f)
            assertTrue(engine.paused) // short gaps between words don't resume it
            Thread.sleep(1600)
            talk(0f)
            assertFalse(engine.paused)

            // Paused by hand during a call: it stays paused.
            talk(0.2f)
            assertTrue(engine.paused)
            c.togglePause(); c.togglePause() // play, then pause again by hand
            Thread.sleep(1600)
            talk(0f)
            assertTrue(engine.paused)

            c.changeVoiceDuck(VoiceDuck.OFF)
            assertTrue(closed)
        } finally {
            scope.cancel()
            AppHooks.voiceMeter = null
        }
    }
}
