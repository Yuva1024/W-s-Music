package echopc

import java.nio.file.Files
import java.nio.file.Path
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume



/**
 * Desktop audio player (JavaFX Media). Stream URLs come from the shared `innertube`/InnerTubeX
 * code; no external player is needed.
 */
class DesktopPlayer(
    private val onState: (PlayerState) -> Unit,
    private val onEnded: () -> Unit = {},
    private val loader: AudioLoader = AudioLoader(),
    /** Called (instead of showing an error) when JavaFX itself cannot start or open a track. */
    private val onEngineFailure: ((String) -> Unit)? = null,
) : PlayerEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mediaPlayer: MediaPlayer? = null
    private var playJob: Job? = null
    private var generation = 0
    private var state = PlayerState()
    private var volume = 0.8

    // JavaFX (audio) costs memory, threads and a second or two of CPU, so it is only started the first
    // time something is played (in parallel with the stream download), never at app launch.
    private val fxReady = java.util.concurrent.CountDownLatch(1)
    private val fxStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var fxUsable = true

    private fun engineFailed(gen: Int, reason: String) {
        if (gen != generation) return
        val handler = onEngineFailure
        if (handler != null) handler(reason) else update { PlayerState(error = reason) }
    }

    private fun ensureFx() {
        if (!fxStarted.compareAndSet(false, true)) return
        // Audio only: skip creating a Direct3D rendering pipeline we would never use.
        System.setProperty("prism.order", "sw")
        Thread({
            try {
                Platform.startup {
                    Platform.setImplicitExit(false)
                    fxReady.countDown()
                }
            } catch (_: IllegalStateException) {
                Platform.setImplicitExit(false) // toolkit was already running
                fxReady.countDown()
            } catch (e: Throwable) {
                Device.log("JavaFX startup failed: ${e.stackTraceToString().take(600)}")
                fxUsable = false
                fxReady.countDown()
            }
        }, "javafx-init").apply { isDaemon = true; start() }
    }

    private fun update(block: PlayerState.() -> PlayerState) {
        state = state.block()
        onState(state)
    }

    /** Plays [videoId] now and starts fetching [nextVideoId] in the background. */
    override fun play(videoId: String, nextVideoId: String?) {
        ensureFx()
        val gen = ++generation
        playJob?.cancel()
        disposePlayer()
        update { PlayerState(loading = true) }
        loader.retainOnly(setOfNotNull(videoId, nextVideoId))
        val current = loader.load(videoId)
        playJob = scope.launch {
            try {
                val file = current.await()
                if (gen != generation) return@launch
                val ready = withContext(Dispatchers.IO) { fxReady.await(30, java.util.concurrent.TimeUnit.SECONDS) }
                if (!ready || !fxUsable) { engineFailed(gen, "JavaFX toolkit did not start"); return@launch }
                startPlayback(file, gen)
                // Current track is ready: now warm up the next one.
                if (nextVideoId != null && gen == generation) loader.load(nextVideoId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (gen == generation) update { PlayerState(error = e.message ?: e.toString()) }
            }
        }
    }

    private suspend fun startPlayback(file: Path, gen: Int) = suspendCancellableCoroutine<Unit> { cont ->
        Platform.runLater {
            try {
                if (gen != generation) { cont.resume(Unit); return@runLater }
                val player = MediaPlayer(Media(file.toUri().toString()))
                player.volume = volume
                mediaPlayer = player
                player.setOnReady {
                    if (gen != generation) { player.dispose(); return@setOnReady }
                    update { copy(loading = false, playing = true, durationMs = player.totalDuration.toMillis().toLong()) }
                    player.play()
                    if (cont.isActive) cont.resume(Unit)
                }
                player.currentTimeProperty().addListener { _, _, t ->
                    if (gen == generation) {
                        val ms = t.toMillis().toLong()
                        if (kotlin.math.abs(ms - state.positionMs) >= 200) update { copy(positionMs = ms) }
                    }
                }
                player.setOnEndOfMedia {
                    if (gen == generation) {
                        update { copy(playing = false, positionMs = 0) }
                        onEnded()
                    }
                }
                player.setOnError {
                    engineFailed(gen, player.error?.message ?: "Playback error")
                    if (cont.isActive) cont.resume(Unit)
                }
                // If the media engine neither plays nor reports an error (missing codecs), give up after a while.
                scope.launch {
                    kotlinx.coroutines.delay(20_000)
                    if (gen == generation && state.loading) engineFailed(gen, "JavaFX media did not become ready")
                }
            } catch (e: Throwable) {
                Device.log("JavaFX media failed: ${e.stackTraceToString().take(600)}")
                engineFailed(gen, e.message ?: e.toString())
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    override fun prefetch(videoId: String) { loader.load(videoId) }

    override fun togglePause() {
        val p = mediaPlayer ?: return
        Platform.runLater {
            if (state.playing) { p.pause(); update { copy(playing = false) } } else { p.play(); update { copy(playing = true) } }
        }
    }

    override fun seek(ms: Long) {
        val p = mediaPlayer ?: return
        Platform.runLater { p.seek(Duration.millis(ms.toDouble())) }
    }

    override fun setVolume(v: Double) {
        volume = v
        val p = mediaPlayer ?: return
        Platform.runLater { p.volume = v }
    }

    private fun disposePlayer() {
        val p = mediaPlayer
        mediaPlayer = null
        if (p != null) Platform.runLater { runCatching { p.stop(); p.dispose() } }
    }

    /** Stops playback without discarding downloaded files (used when switching engines). */
    fun release() {
        generation++
        playJob?.cancel()
        disposePlayer()
    }

    override fun stop() {
        release()
        loader.retainOnly(emptySet())
        update { PlayerState() }
    }
}


