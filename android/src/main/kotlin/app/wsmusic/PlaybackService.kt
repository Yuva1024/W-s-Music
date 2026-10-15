package app.wsmusic

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
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
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Plays music in the background with notification and lock-screen controls.
 *
 * Songs arrive as `wsmusic://song?id=<videoId>`; the shared loader finds the YouTube Music stream and downloads it
 * to a temp file, which the player reads. Next / previous in the notification go to the shared Controller's queue.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this)) { spec ->
            if (spec.uri.scheme != SCHEME) return@Factory spec
            val id = spec.uri.getQueryParameter("id") ?: throw IOException("No song id in ${spec.uri}")
            Diagnostics.add("Loading $id")
            val file = try {
                runBlocking { Streams.loader.load(id).await() }
            } catch (e: CancellationException) {
                Diagnostics.add("Skipped $id (another song was picked)")
                throw IOException("Skipped", e)
            } catch (e: Exception) {
                Diagnostics.add("Load failed for $id: ${e.message}")
                throw IOException(e.message ?: "Could not load the song", e)
            }
            Diagnostics.add("Loaded $id: ${file.toFile().length() / 1024} KB")
            spec.withUri(Uri.fromFile(file.toFile()))
        }
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { Diagnostics.add("Player state ${stateName(state)}") }
        })
        // The player holds one song; the queue lives in the shared Controller, so next/previous are sent there.
        val player = object : ForwardingPlayer(exo) {
            private val queueCommands = listOf(
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon().addAll(*queueCommands.toIntArray()).build()
            override fun isCommandAvailable(command: Int) = command in queueCommands || super.isCommandAvailable(command)
            override fun seekToNext() = WsApp.instance.controller.skipNext()
            override fun seekToNextMediaItem() = WsApp.instance.controller.skipNext()
            override fun seekToPrevious() = WsApp.instance.controller.skipPrevious()
            override fun seekToPreviousMediaItem() = WsApp.instance.controller.skipPrevious()
        }
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            // Items sent from the app arrive without their URI; rebuild it from the song id.
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
