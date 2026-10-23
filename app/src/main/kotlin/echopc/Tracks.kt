package echopc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.alpha
import echopc.db.artistKey
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.innertube.models.SongItem

internal fun fmtTime(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

/** Accent taken from a cover image (for page headers), or [fallback] while it loads / when there is none. */
@Composable
fun rememberCoverAccent(cover: String?, fallback: Accent = Accent(Ws.defaultAccent, Ws.defaultAccent2)): Accent {
    val accent by produceState(fallback, cover) {
        value = cover?.takeIf { it.isNotBlank() }?.let { ThumbCache.load(it, 128)?.dominant }?.let(::accentFrom) ?: fallback
    }
    return accent
}

/** Art for a page header: a cover image, or an icon tile (Liked songs, history). */
sealed interface CoverArt {
    data class Image(val url: String?, val seed: String) : CoverArt
    data class Icon(val icon: ImageVector) : CoverArt
}

/**
 * A playlist-like page: colour-flooded header, action row, then the track table, all scrolling together.
 * Used by Liked songs, local and online playlists, albums, history and mixes.
 */
@Composable
fun CollectionPage(
    c: Controller,
    kind: String,
    title: String,
    subtitle: String,
    art: CoverArt,
    songs: List<SongItem>,
    emptyText: String,
    modifier: Modifier = Modifier,
    accent: Accent = rememberCoverAccent((art as? CoverArt.Image)?.url),
    extraColumn: Pair<String, (Int) -> String>? = null,
    rowTrailing: (@Composable (Int) -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // The page's own colour (from its cover) also drives its play button and highlights.
    CompositionLocalProvider(LocalAccent provides accent) { BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 640.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item("header") { CollectionHeader(kind, title, subtitle, art, accent) }
            item("actions") {
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(accent.flood.copy(alpha = 0.35f), Color.Transparent)))) {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        val inList = c.queue.current?.id?.let { id -> songs.any { it.id == id } } == true
                        PlayButton(
                            playing = inList && c.playback.playing, loading = false, enabled = songs.isNotEmpty(),
                            onClick = { if (inList) c.togglePause() else c.playCollection(songs) },
                            size = 56.dp, onAccent = true,
                        )
                        val context = routeContext(c.route)
                        val mode = c.shuffleMode(context)
                        ShuffleButton(mode, shuffleActionLabel(mode, c.smartAvailable(context)), { c.cycleShuffle(context) }, size = 40.dp, iconSize = 28.dp, enabled = songs.isNotEmpty())
                        if (c.route != Route.Downloads && c.route != Route.LocalFiles) DownloadAllButton(c, songs)
                        if (mode == ShuffleMode.SMART) MetaText(if (c.smartLoading) "Finding recommendations" else "Smart Shuffle: recommendations mixed in")
                        actions()
                    }
                }
            }
            if (songs.isEmpty()) {
                item("empty") {
                    Text(emptyText, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
                }
            } else {
                // Many online playlists carry no album names: drop the column instead of showing it empty.
                val albums = wide && songs.any { !it.album?.name.isNullOrBlank() }
                item("columns") { TrackColumns(wide, extraColumn?.first, albums) }
                trackRows(c, songs, wide, extraColumn?.second, rowTrailing, albums)
            }
        }
    } }
}

@Composable
private fun CollectionHeader(kind: String, title: String, subtitle: String, art: CoverArt, accent: Accent) {
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(accent.flood, accent.flood.copy(alpha = 0.55f))))) {
        Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 24.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            when (art) {
                is CoverArt.Image -> Thumb(art.url, 200.dp, corner = 6.dp, seed = art.seed)
                is CoverArt.Icon -> Box(
                    Modifier.size(200.dp).clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(listOf(accent.primary, accent.secondary.copy(alpha = 0.6f)))),
                    contentAlignment = Alignment.Center,
                ) { Icon(art.icon, null, Modifier.size(72.dp), tint = accent.onPrimary) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(kind, style = MaterialTheme.typography.titleSmall, color = Ws.text)
                val style = when {
                    title.length > 40 -> MaterialTheme.typography.displaySmall
                    title.length > 18 -> MaterialTheme.typography.displayMedium
                    else -> MaterialTheme.typography.displayLarge
                }
                Text(title, style = style, color = Ws.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ws.text.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Column titles above a track table. */
@Composable
fun TrackColumns(wide: Boolean, extra: String? = null, albums: Boolean = wide) {
    Column(Modifier.padding(horizontal = 24.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("#", Modifier.width(24.dp), style = MaterialTheme.typography.bodyMedium, color = Ws.muted, textAlign = TextAlign.End)
            Text("Title", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
            if (albums) Text("Album", Modifier.weight(0.7f), style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
            if (wide && extra != null) Text(extra, Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
            Box(Modifier.width(36.dp)) // like
            Icon(Icons.Rounded.Schedule, "Duration", Modifier.width(44.dp).size(16.dp), tint = Ws.muted)
            Box(Modifier.width(32.dp)) // menu
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ws.border))
        Box(Modifier.height(8.dp))
    }
}

/** Track rows for any LazyColumn (pages compose their own headers above them). */
fun LazyListScope.trackRows(
    c: Controller,
    songs: List<SongItem>,
    wide: Boolean,
    extra: ((Int) -> String)? = null,
    trailing: (@Composable (Int) -> Unit)? = null,
    albums: Boolean = wide,
) {
    itemsIndexed(songs, key = { i, s -> "row-$i-${s.id}" }) { i, song ->
        Box(Modifier.padding(horizontal = 24.dp)) { TrackRow(c, song, i, songs, wide, extra?.invoke(i), trailing, albums) }
    }
}

@Composable
fun TrackRow(
    c: Controller,
    song: SongItem,
    index: Int,
    songs: List<SongItem>,
    wide: Boolean = true,
    extra: String? = null,
    trailing: (@Composable (Int) -> Unit)? = null,
    albums: Boolean = wide,
) {
    val accent = LocalAccent.current
    val current = c.queue.current?.id == song.id
    val liked = song.id in c.likedIds
    // Hidden here, not wanted, or by a blocked artist: shown dimmed and skipped when playing.
    val blocked = c.blocks.blocked(song, routeContext(c.route))
    HoverRow(
        { c.playFromList(songs, index) }, selected = false,
        menu = { close -> SongMenuItems(c, song, routeContext(c.route), close) }, // right-click anywhere on the row
    ) { hovered ->
        Row(Modifier.height(56.dp).padding(horizontal = 16.dp).alpha(if (blocked) 0.4f else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.CenterEnd) {
                when {
                    hovered -> Icon(Icons.Rounded.PlayArrow, "Play", Modifier.size(18.dp), tint = Ws.text)
                    current -> EqBars(c.playback.playing)
                    else -> Text("${index + 1}", style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
                }
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Thumb(song.thumbnail, 40.dp, corner = 4.dp, seed = song.title)
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.bodyLarge, color = if (current) accent.highlight else Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artists.joinToString { it.name }.ifBlank { "Unknown artist" }, style = MaterialTheme.typography.bodyMedium, color = if (hovered) Ws.text else Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (albums) Text(song.album?.name.orEmpty(), Modifier.weight(0.7f), style = MaterialTheme.typography.bodyMedium, color = if (hovered) Ws.text else Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (wide && extra != null) Text(extra, Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium, color = Ws.muted, maxLines = 1)
            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                // Always in the tree (keyboard reachable); invisible until the row is hovered unless liked.
                WsIconButton(
                    if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Unlike" else "Like", { c.toggleLike(song) },
                    size = 32.dp, iconSize = 18.dp,
                    tint = when { liked -> accent.highlight; hovered -> Ws.muted; else -> Color.Transparent },
                    hoverTint = if (liked) accent.primary else Ws.text,
                )
            }
            Text(song.duration?.takeIf { it > 0 }?.let { fmtTime(it * 1000L) }.orEmpty(), Modifier.width(44.dp), style = MaterialTheme.typography.bodyMedium, color = Ws.muted, textAlign = TextAlign.End)
            Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                if (trailing != null) trailing(index) else SongMenu(c, song, visible = hovered)
            }
        }
    }
}

/** "…" button for a song: opens [SongMenuItems]. Invisible (but focusable) until the row is hovered. */
@Composable
fun SongMenu(c: Controller, song: SongItem, visible: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        WsIconButton(
            Icons.Rounded.MoreHoriz, "More options for ${song.title}", { open = true }, size = 32.dp, iconSize = 20.dp,
            tint = if (visible || open) Ws.muted else Color.Transparent,
        )
        DropdownMenu(open, { open = false }, containerColor = Ws.surfaceTop, shape = RoundedCornerShape(4.dp)) {
            SongMenuItems(c, song, routeContext(c.route), close = { open = false })
        }
    }
}

/**
 * The options for one song, shared by the "…" button and the right-click menu on rows.
 * [context] is the page or queue the song is in (for "hide from this playlist"); [queueIndex] adds
 * "Remove from queue" when the song is a row of the queue.
 */
@Composable
fun SongMenuItems(c: Controller, song: SongItem, context: String, close: () -> Unit, queueIndex: Int? = null) {
    var inPlaylists by remember { mutableStateOf(false) }
    val playlists by c.library.playlists.collectAsState(emptyList())
    if (!inPlaylists) {
        MenuItem("Play next", Icons.Rounded.PlayArrow) { c.playNext(song); close() }
        MenuItem("Add to queue", Icons.Rounded.QueueMusic) { c.addToQueue(song); close() }
        MenuItem("Add to playlist", Icons.Rounded.PlaylistAdd) { inPlaylists = true }
        if (!OfflineFiles.isLocal(song.id)) {
            if (c.downloads.isDownloaded(song.id)) MenuItem("Remove download", Icons.Rounded.DownloadDone) { c.downloads.remove(song); close() }
            else if (song.id !in c.downloads.active) MenuItem("Download", Icons.Rounded.Download) { c.downloads.download(listOf(song)); close() }
        }
        if (queueIndex != null && queueIndex != c.queue.index) MenuItem("Remove from queue", Icons.Rounded.Close) { c.removeFromQueue(queueIndex); close() }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Ws.border)
        FeedbackItems(c, song, context, close)
    } else {
        MenuItem("New playlist", Icons.Rounded.Add) { c.newPlaylistFor = song; c.newPlaylistOpen = true; close() }
        playlists.forEach { p -> MenuItem(p.playlist.name, Icons.Rounded.MusicNote) { c.addToPlaylist(p.playlist.id, song); close() } }
    }
}

/** "Tell the recommender" items: each one has an inverse when it's already set. */
@Composable
private fun FeedbackItems(c: Controller, song: SongItem, context: String, close: () -> Unit) {
    val b = c.blocks
    if (song.id in b.notInterested) MenuItem("Recommend this again", Icons.Rounded.ThumbUp) { c.recommendAgain(song); close() }
    else MenuItem("Not interested", Icons.Rounded.ThumbDown) { c.notInterested(song); close() }
    c.hideLabel(context)?.let { label ->
        if (song.id in b.hidden[context].orEmpty()) MenuItem("Show in this list again", Icons.Rounded.Visibility) { c.unhideInContext(song, context); close() }
        else MenuItem(label, Icons.Rounded.VisibilityOff) { c.hideInContext(song, context); close() }
    }
    val artist = song.artists.firstOrNull()?.name
    if (artist != null) {
        if (song.artistKey() in b.bannedArtists) MenuItem("Allow $artist to play", Icons.Rounded.CheckCircle) { c.allowArtist(song); close() }
        else MenuItem("Don't play $artist", Icons.Rounded.Block) { c.banArtist(song); close() }
    }
    if (song.id in b.excluded) MenuItem("Include in your taste profile", Icons.Rounded.Tune) { c.toggleExcludeFromTaste(song); close() }
    else MenuItem("Exclude from your taste profile", Icons.Rounded.Tune) { c.toggleExcludeFromTaste(song); close() }
}

@Composable
fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = Ws.text) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp), tint = Ws.muted) },
        onClick = onClick,
    )
}
