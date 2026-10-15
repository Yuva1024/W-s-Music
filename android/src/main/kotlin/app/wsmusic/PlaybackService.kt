package app.wsmusic

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import echopc.AudioLoader
import echopc.StreamLog
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * Plays music in the background with notification and lock-screen controls.
 *
 * Songs are queued as `wsmusic://<videoId>`. When the player needs one, the same loader the desktop app uses
 * finds the YouTube Music stream and downloads it to a temp file, which the player then reads. The player asks
 * for the next song while the current one plays, so it is usually ready before it is needed.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val loader = AudioLoader()

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        StreamLog.sink = { Log.w("WsMusic", it); Diagnostics.add(it) }
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this)) { spec ->
            if (spec.uri.scheme != SCHEME) return@Factory spec
            val id = spec.uri.getQueryParameter("id") ?: throw IOException("No song id in ${spec.uri}")
            Diagnostics.add("Loading $id")
            val file = try {
                runBlocking { loader.load(id).await() }
            } catch (e: Exception) {
                Diagnostics.add("Load failed for $id: ${e.message}")
                throw IOException(e.message ?: "Could not load the song", e)
            }
            Diagnostics.add("Loaded $id: ${file.toFile().length() / 1024} KB")
            spec.withUri(Uri.fromFile(file.toFile()))
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Diagnostics.add("Player error ${error.errorCodeName}: ${error.cause?.message ?: error.message}")
            }
            override fun onPlaybackStateChanged(state: Int) { Diagnostics.add("Player state ${stateName(state)}") }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Keep only the current and next downloads; older temp files are deleted.
                val keep = listOfNotNull(mediaItem?.mediaId, player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { player.getMediaItemAt(it).mediaId })
                loader.retainOnly(keep.toSet())
            }
        })
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            // Items sent from the app's screen arrive without their URI; rebuild it from the song id.
            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
            ): ListenableFuture<MutableList<MediaItem>> =
                Futures.immediateFuture(mediaItems.map { it.buildUpon().setUri(songUri(it.mediaId)).build() }.toMutableList())
        }).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    companion object {
        const val SCHEME = "wsmusic"
        fun stateName(state: Int) = when (state) { Player.STATE_IDLE -> "idle"; Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "?" }
        // The id goes in a query parameter: YouTube ids contain "_" and "-", which are not safe in the host part.
        fun songUri(videoId: String): Uri = Uri.parse("$SCHEME://song?id=" + Uri.encode(videoId))
    }
}
