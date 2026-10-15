package app.wsmusic

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Second step of the phone app: search and play, with background playback and lock-screen controls. */
class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) // for the playback notification
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { SearchAndPlay(controller) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controller = runCatching { future.get() }.getOrNull() }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }
}

private fun SongItem.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(PlaybackService.songUri(id))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artists.joinToString { it.name })
            .setArtworkUri(thumbnail.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) })
            .build(),
    )
    .build()

@Composable
private fun SearchAndPlay(controller: MediaController?) {
    var query by remember { mutableStateOf("") }
    var songs by remember { mutableStateOf(listOf<SongItem>()) }
    var message by remember { mutableStateOf("Search for a song or an artist.") }
    val scope = rememberCoroutineScope()

    // What is playing, kept in sync with the background player.
    var nowTitle by remember { mutableStateOf<String?>(null) }
    var nowArtist by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stateText by remember { mutableStateOf("") }
    var showDetails by remember { mutableStateOf(false) }
    DisposableEffect(controller) {
        val c = controller
        fun sync() {
            nowTitle = c?.mediaMetadata?.title?.toString()
            nowArtist = c?.mediaMetadata?.artist?.toString().orEmpty()
            playing = c?.isPlaying == true
            loading = c?.playbackState == Player.STATE_BUFFERING
            stateText = if (c == null) "not connected" else PlaybackService.stateName(c.playbackState) + (if (c.playWhenReady) ", play requested" else "") + (if (c.isPlaying) ", playing" else "")
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync()
            override fun onPlayerError(e: PlaybackException) { error = "Couldn't play this song: ${e.cause?.message ?: e.message}" }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { error = null }
        }
        c?.addListener(listener)
        sync()
        onDispose { c?.removeListener(listener) }
    }

    fun search() {
        if (query.isBlank()) return
        scope.launch {
            message = "Searching…"
            val found = withContext(Dispatchers.IO) { YouTube.search(query, YouTube.SearchFilter.FILTER_SONG) }
            found.onSuccess { r -> songs = r.items.filterIsInstance<SongItem>(); message = if (songs.isEmpty()) "Nothing found." else "" }
                .onFailure { message = "Search failed. Check your connection." }
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text("W's MUSIC", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("What do you want to play?") })
                Button(::search) { Text("Search") }
            }
            if (message.isNotEmpty()) Text(message, Modifier.padding(vertical = 12.dp), color = Color.Gray)
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(songs) { index, song ->
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            error = null
                            controller?.run {
                                setMediaItems(songs.map { it.toMediaItem() }, index, 0L)
                                prepare()
                                play()
                            }
                        }.padding(vertical = 10.dp),
                    ) {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artists.joinToString { it.name }, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // Mini player
        if (nowTitle != null || Diagnostics.lines.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(Color(0xFF282828)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(nowTitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (loading) "Loading…" else nowArtist, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton({ controller?.seekToPreviousMediaItem() }) { Text("Prev") }
                    TextButton({ controller?.run { if (isPlaying) pause() else play() } }) { Text(if (playing) "Pause" else "Play") }
                    TextButton({ controller?.seekToNextMediaItem() }) { Text("Next") }
                }
                error?.let { Text(it, color = Color(0xFFFF6B6B), maxLines = 2) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Player: $stateText", color = Color.Gray, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton({ showDetails = !showDetails }) { Text(if (showDetails) "Hide" else "Details") }
                }
                if (showDetails) Column { Diagnostics.lines.takeLast(12).forEach { Text(it, color = Color.Gray, maxLines = 2, style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }
}
