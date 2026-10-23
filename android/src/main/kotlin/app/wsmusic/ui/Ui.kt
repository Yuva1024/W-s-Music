package app.wsmusic.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.SolidColor
import app.wsmusic.Artwork
import coil3.compose.AsyncImage
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import echopc.Controller

/** Spotify-like dark palette (same values as the desktop app). */
object Ws {
    val bg = Color(0xFF121212)
    val surface = Color(0xFF181818)
    val surfaceHi = Color(0xFF282828)
    val surfaceTop = Color(0xFF333333)
    val text = Color.White
    val muted = Color(0xFFB3B3B3)
    val faint = Color(0xFF7A7A7A)
    val danger = Color(0xFFF3727F)
}

val WsColors = darkColorScheme(
    primary = Color(0xFF8B5CF6), onPrimary = Color.White,
    background = Ws.bg, onBackground = Ws.text,
    surface = Ws.bg, onSurface = Ws.text,
    surfaceVariant = Ws.surfaceHi, onSurfaceVariant = Ws.muted,
    surfaceContainer = Ws.surfaceHi, surfaceContainerHigh = Ws.surfaceHi, surfaceContainerHighest = Ws.surfaceTop,
)

/** Icons that are not in the small built-in set, from Material Design's path data. */
object WsIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.White)).build()

    val Pause = icon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z")
    val SkipNext = icon("SkipNext", "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z")
    val SkipPrevious = icon("SkipPrevious", "M6 6h2v12H6zm3.5 6l8.5 6V6z")
    val Shuffle = icon("Shuffle", "M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z")
    val Repeat = icon("Repeat", "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z")
    val RepeatOne = icon("RepeatOne", "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z")
    val Library = icon("Library", "M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-2 5h-3v5.5c0 1.38-1.12 2.5-2.5 2.5S10 13.88 10 12.5s1.12-2.5 2.5-2.5c.57 0 1.08.19 1.5.51V5h4v2zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6z")
    val Queue = icon("Queue", "M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z")
    val Download = icon("Download", "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z")
    val Sync = icon("Sync", "M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z")
    val Devices = icon("Devices", "M4 6h18V4H4c-1.1 0-2 .9-2 2v11H0v3h14v-3H4V6zm19 2h-6c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h6c.55 0 1-.45 1-1V9c0-.55-.45-1-1-1zm-1 9h-4v-7h4v7z")
    val DownloadDone = icon("DownloadDone", "M5 18h14v2H5v-2zm4.6-2.7L5 10.7l2-1.9 2.6 2.6L17 4l2 2-9.4 9.3z")
    val Folder = icon("Folder", "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z")
    val DragHandle = icon("DragHandle", "M20 9H4v2h16V9zM4 15h16v-2H4v2z")
    val Close = icon("Close", "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z")
    val History = icon("History", "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z")
}

/** Album art (rounded square, or a circle for artists); a coloured letter block when there is no picture. */
@Composable
fun Art(url: String?, size: Dp, modifier: Modifier = Modifier, round: Boolean = false, corner: Dp = 6.dp) {
    val shape = if (round) CircleShape else RoundedCornerShape(corner)
    val px = (size.value * 2.5f).toInt().coerceIn(60, 544)
    Box(modifier.size(size).clip(shape).background(Ws.surfaceTop)) {
        if (!url.isNullOrBlank()) AsyncImage(Artwork.sized(url, px), null, Modifier.size(size), contentScale = ContentScale.Crop)
    }
}

/** A song in a list: art, title, artists; tap to play, ⋮ (or a long press) for its options. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(c: Controller, song: SongItem, onClick: () -> Unit, index: Int? = null) {
    var menu by remember { mutableStateOf(false) }
    val current = c.queue.current?.id == song.id
    // Swipe right: add to queue. Swipe left: play next.
    Swipeable(
        right = SwipeAction("Add to queue", WsIcons.Queue, Color(0xFF1DB954)) { c.addToQueue(song) },
        left = SwipeAction("Play next", WsIcons.SkipNext, Color(0xFF3D5AFE)) { c.playNext(song) },
    ) {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (index != null) Text("${index + 1}", Modifier.width(22.dp), color = Ws.muted, style = MaterialTheme.typography.bodyMedium)
            Art(song.thumbnail, 48.dp)
            Column(Modifier.weight(1f)) {
                Text(song.title, color = if (current) c.accentHighlight() else Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (song.id in c.likedIds) Icon(Icons.Filled.Favorite, null, Modifier.size(12.dp), tint = c.accentHighlight())
                    Text(
                        (if (song.isVideoSong) "Video • " else "") + song.artists.joinToString { it.name },
                        color = Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More options", tint = Ws.muted) }
                SongMenu(c, song, menu) { menu = false }
            }
        }
    }
}

fun Controller.accentHighlight(): Color = accent.highlight

@Composable
fun SongMenu(c: Controller, song: SongItem, open: Boolean, onClose: () -> Unit) {
    var pickPlaylist by remember { mutableStateOf(false) }
    DropdownMenu(open, onClose, containerColor = Ws.surfaceHi) {
        val liked = song.id in c.likedIds
        DropdownMenuItem({ Text(if (liked) "Remove from Liked Songs" else "Save to Liked Songs") }, { c.toggleLike(song); onClose() })
        DropdownMenuItem({ Text("Play next") }, { c.playNext(song); onClose() })
        DropdownMenuItem({ Text("Add to queue") }, { c.addToQueue(song); onClose() })
        DropdownMenuItem({ Text("Add to playlist…") }, { pickPlaylist = true; onClose() })
        if (!echopc.OfflineFiles.isLocal(song.id)) when {
            c.downloads.isDownloaded(song.id) -> DropdownMenuItem({ Text("Remove download") }, { c.downloads.remove(song); onClose() })
            song.id in c.downloads.active -> DropdownMenuItem({ Text("Downloading…") }, {}, enabled = false)
            else -> DropdownMenuItem({ Text("Download") }, { c.downloads.download(listOf(song)); onClose() })
        }
        DropdownMenuItem({ Text("Go to song radio") }, { c.playMix(song); onClose() })
        DropdownMenuItem({ Text("Not interested") }, { c.notInterested(song); onClose() })
        DropdownMenuItem({ Text("Don't play this artist") }, { c.banArtist(song); onClose() })
    }
    if (pickPlaylist) PlaylistPicker(c, song) { pickPlaylist = false }
}

@Composable
fun PlaylistPicker(c: Controller, song: SongItem, onClose: () -> Unit) {
    val playlists by c.library.playlists.collectAsState(emptyList())
    AlertDialog(
        onDismissRequest = onClose, containerColor = Ws.surfaceHi,
        title = { Text("Add to playlist") },
        text = {
            Column {
                TextButton({ c.newPlaylistFor = song; c.newPlaylistOpen = true; onClose() }) { Text("+ New playlist") }
                playlists.forEach { p -> TextButton({ c.addToPlaylist(p.playlist.id, song); onClose() }) { Text(p.playlist.name, color = Ws.text) } }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } },
    )
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ws.surfaceHi,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("Name") }) },
        confirmButton = { TextButton({ onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** A cover card for playlists, albums, artists, mixes and songs in a horizontal shelf. */
@Composable
fun Card(title: String, subtitle: String, thumb: String?, onClick: () -> Unit, round: Boolean = false, label: String? = null) {
    Column(Modifier.width(150.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            Art(thumb, 134.dp, round = round)
            if (label != null) Text(
                label, Modifier.align(Alignment.BottomStart).padding(8.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 2,
            )
        }
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = Ws.text)
        Text(subtitle, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Ws.muted)
    }
}

/** A card for any YouTube Music item; tapping opens it (songs play). */
@Composable
fun ItemCard(c: Controller, item: YTItem, siblings: List<YTItem>) {
    val sub = when (item) {
        is SongItem -> item.artists.joinToString { it.name }
        is AlbumItem -> listOfNotNull("Album", item.year?.toString(), item.artists?.joinToString { it.name }).joinToString(" • ")
        is PlaylistItem -> item.author?.name?.let { "Playlist • $it" } ?: "Playlist"
        is ArtistItem -> "Artist"
        else -> ""
    }
    Card(item.title, sub, item.thumbnail, { c.openItem(item, siblings) }, round = item is ArtistItem)
}

@Composable
fun Shelf(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Text(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), content = content)
    }
}

@Composable
fun ItemShelf(c: Controller, title: String, items: List<YTItem>) {
    Shelf(title) { itemsIndexed(items) { _, it -> ItemCard(c, it, items) } }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Ws.text)
}

@Composable
fun Message(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(16.dp), color = Ws.muted, style = MaterialTheme.typography.bodyLarge)
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
