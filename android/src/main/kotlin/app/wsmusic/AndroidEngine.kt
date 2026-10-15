package app.wsmusic

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.music.innertube.models.SongItem
import echopc.PlayerEngine
import echopc.PlayerState

/**
 * The shared Controller's player on Android. The Controller keeps the queue; this plays one song at a time
 * through the background [PlaybackService] (so music keeps going with the screen off) and reports progress back.
 */
class AndroidEngine(
    private val context: Context,
    private val onState: (PlayerState) -> Unit,
    private val onEnded: () -> Unit,
) : PlayerEngine {
    /** Title, artist and artwork for the notification, looked up in the Controller's queue. */
    var songLookup: (String) -> SongItem? = { null }

    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayList<(MediaController) -> Unit>()
    private var currentId: String? = null
    private var nextId: String? = null
    private var endedFor: String? = null
    private var error: String? = null
    private var volume = 1f

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player.playbackState == Player.STATE_READY) nextId?.let { Streams.loader.load(it) } // warm up the next song
            if (player.playbackState == Player.STATE_ENDED && currentId != null && endedFor != currentId) {
                endedFor = currentId
                onEnded()
            }
            publish()
        }

        override fun onPlayerError(e: PlaybackException) {
            error = e.cause?.message ?: e.message ?: "Playback error"
            Diagnostics.add("Player error: $error")
            publish()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            publish()
            if (controller?.isPlaying == true) main.postDelayed(this, 500)
        }
    }

    private fun publish() {
        val c = controller ?: return
        onState(
            PlayerState(
                loading = error == null && currentId != null && (c.playbackState == Player.STATE_BUFFERING || (c.playWhenReady && c.playbackState == Player.STATE_IDLE)),
                playing = c.isPlaying,
                positionMs = c.currentPosition.coerceAtLeast(0),
                durationMs = c.duration.takeIf { it > 0 } ?: 0,
                error = error,
            ),
        )
        if (c.isPlaying) { main.removeCallbacks(ticker); main.postDelayed(ticker, 500) }
    }

    private fun withPlayer(action: (MediaController) -> Unit) {
        main.post {
            val c = controller
            if (c != null) action(c) else { pending += action; connect() }
        }
    }

    private fun connect() {
        if (connecting) return
        connecting = true
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) { Diagnostics.add("Could not connect to the player"); pending.clear(); return@addListener }
            controller = c
            c.volume = volume
            c.addListener(listener)
            val actions = pending.toList(); pending.clear()
            actions.forEach { it(c) }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun item(videoId: String): MediaItem {
        val song = songLookup(videoId)
        return MediaItem.Builder()
            .setMediaId(videoId)
            .setUri(PlaybackService.songUri(videoId))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song?.title)
                    .setArtist(song?.artists?.joinToString { it.name })
                    .setArtworkUri(song?.thumbnail?.takeIf { it.isNotBlank() }?.let { Uri.parse(Artwork.sized(it, 544)) })
                    .build(),
            )
            .build()
    }

    override fun play(videoId: String, nextVideoId: String?) {
        Streams.loader.retainOnly(setOfNotNull(videoId, nextVideoId))
        withPlayer { c ->
            currentId = videoId; nextId = nextVideoId; endedFor = null; error = null
            c.setMediaItem(item(videoId))
            c.prepare()
            c.play()
            publish()
        }
    }

    override fun prefetch(videoId: String) {
        nextId = videoId
        Streams.loader.load(videoId)
    }

    override fun togglePause() = withPlayer { c ->
        when {
            c.isPlaying -> c.pause()
            c.playbackState == Player.STATE_IDLE && currentId != null -> { error = null; c.prepare(); c.play() } // after an error: try again
            else -> c.play()
        }
        publish()
    }

    override fun seek(ms: Long) = withPlayer { it.seekTo(ms) }

    override fun setVolume(v: Double) {
        volume = v.toFloat().coerceIn(0f, 1f)
        withPlayer { it.volume = volume }
    }

    override fun stop() = withPlayer { c ->
        currentId = null; nextId = null
        c.stop(); c.clearMediaItems()
        publish()
    }
}
