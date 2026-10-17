package echopc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.innertube.models.SongItem
import echopc.lyrics.LyricsUtils

/** Right-hand panel: the Now Playing view or the queue. */
@Composable
fun RightPanel(c: Controller) {
    Box(Modifier.width(340.dp).fillMaxHeight().panel()) {
        when (c.panelTab) {
            PanelTab.NOW_PLAYING -> NowPlayingView(c)
            PanelTab.QUEUE -> QueueView(c)
        }
    }
}

@Composable
private fun PanelHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        WsIconButton(Icons.Rounded.Close, "Close", onClose, size = 32.dp, iconSize = 18.dp)
    }
}

// =====================================================================================
//  Now Playing
// =====================================================================================

@Composable
private fun NowPlayingView(c: Controller) {
    val accent = LocalAccent.current
    val song = c.nowQueue.current
    // The panel is flooded with the album-art colour, fading into the panel grey.
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to accent.flood, 0.55f to Ws.surface))) {
        if (song == null) {
            Column {
                PanelHeader("Now playing", { c.panelOpen = false })
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Nothing is playing", style = MaterialTheme.typography.titleLarge, color = Ws.text)
                    Text("Pick a song, album or mix and it shows up here with its lyrics and what's next.", style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
                    OutlineButton("Search", { c.navigate(Route.Search) })
                }
            }
            return@Box
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("header") { PanelHeader(song.album?.name ?: song.artists.firstOrNull()?.name ?: "Now playing", { c.panelOpen = false }) }
            item("art") {
                BoxWithConstraints(Modifier.padding(horizontal = 16.dp)) { Thumb(song.thumbnail, maxWidth, corner = 8.dp, seed = song.title) }
            }
            item("title") {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(song.title, style = MaterialTheme.typography.headlineMedium, color = Ws.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(song.artists.joinToString { it.name }, style = MaterialTheme.typography.bodyLarge, color = Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val liked = song.id in c.likedIds
                    WsIconButton(
                        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Remove from Liked Songs" else "Save to Liked Songs", { c.toggleLike(song) },
                        size = 36.dp, iconSize = 22.dp, tint = if (liked) accent.highlight else Ws.muted,
                    )
                }
            }
            item("lyrics") { LyricsPreview(c) }
            item("credits") { CreditsCard(song) }
            c.nowQueue.upNext?.takeIf { it.id != song.id || c.nowQueue.repeat == RepeatMode.ONE }?.let { next ->
                item("next") { NextInQueueCard(c, next) }
            }
        }
    }
}

/** A few lines around the current one on an accent card; click opens the full lyrics page. */
@Composable
private fun LyricsPreview(c: Controller) {
    val accent = LocalAccent.current
    val state = c.lyrics as? LyricsUiState.Loaded ?: return
    val current by remember(state) { derivedStateOf { if (state.synced) LyricsUtils.findCurrentLineIndex(state.lines, c.positionMs) else -1 } }
    val from = (current - 1).coerceAtLeast(0)
    val lines = state.lines.drop(from).take(5)
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(accent.primary)
            .clickable { c.toggleLyrics() }.handCursor().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Lyrics", style = MaterialTheme.typography.titleMedium, color = accent.onPrimary)
        lines.forEachIndexed { i, line ->
            val active = from + i == current
            Text(
                line.text, style = MaterialTheme.typography.titleMedium,
                color = if (active) accent.onPrimary else accent.onPrimary.copy(alpha = 0.55f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Text("Show lyrics", style = MaterialTheme.typography.labelLarge, color = accent.onPrimary, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CreditsCard(song: SongItem) {
    if (song.artists.isEmpty()) return
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Ws.surfaceHi).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Credits", style = MaterialTheme.typography.titleMedium, color = Ws.text)
        song.artists.forEachIndexed { i, a ->
            Column {
                Text(a.name, style = MaterialTheme.typography.bodyLarge, color = Ws.text)
                Text(if (i == 0) "Main artist" else "Artist", style = MaterialTheme.typography.bodySmall, color = Ws.muted)
            }
        }
        song.album?.name?.takeIf { it.isNotBlank() }?.let {
            Column {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Album", style = MaterialTheme.typography.bodySmall, color = Ws.muted)
            }
        }
    }
}

@Composable
private fun NextInQueueCard(c: Controller, next: SongItem) {
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Ws.surfaceHi).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Next in queue", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Ws.text)
            Text("Open queue", Modifier.clickable { c.togglePanel(PanelTab.QUEUE) }.handCursor(), style = MaterialTheme.typography.labelMedium, color = Ws.muted)
        }
        HoverRow({ c.skipNext() }) { _ ->
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Thumb(next.thumbnail, 44.dp, corner = 4.dp, seed = next.title)
                Column(Modifier.weight(1f)) {
                    Text(next.title, style = MaterialTheme.typography.bodyLarge, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(next.artists.joinToString { it.name }, style = MaterialTheme.typography.bodySmall, color = Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// =====================================================================================
//  Queue
// =====================================================================================

@Composable
private fun QueueView(c: Controller) {
    val q = c.nowQueue
    val listState = rememberLazyListState()
    LaunchedEffect(q.index) { if (q.index > 0) listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Queue", { c.panelOpen = false })
        if (q.items.isEmpty()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your queue is empty", style = MaterialTheme.typography.titleLarge, color = Ws.text)
                Text("Play a song and everything after it lines up here. With autoplay on, similar songs keep it going.", style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
                OutlineButton("Search for something new", { c.navigate(Route.Search) })
            }
            return@Column
        }
        val upcoming = q.items.withIndex().drop(q.index + 1)
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
            q.current?.let { now ->
                item("now-title") { QueueSectionTitle("Now playing") }
                item("now") { QueueRow(c, now, q.index, current = true) }
            }
            if (upcoming.isNotEmpty()) item("next-title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QueueSectionTitle("Next up", Modifier.weight(1f))
                    WsIconButton(Icons.Rounded.Shuffle, "Shuffle what's next", { c.shuffleQueue() }, size = 32.dp, iconSize = 18.dp, enabled = upcoming.size > 1)
                    WsIconButton(Icons.Rounded.Delete, "Clear the queue", { c.clearQueue() }, size = 32.dp, iconSize = 18.dp)
                }
            }
            if (c.smartLoading) item("smart-loading") { MetaText("Finding Smart Shuffle recommendations", Modifier.padding(start = 8.dp, bottom = 6.dp)) }
            itemsIndexed(upcoming, key = { _, v -> "q-${v.index}-${v.value.id}" }) { _, v -> QueueRow(c, v.value, v.index, current = false) }
        }
    }
}

@Composable
private fun QueueSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 8.dp, top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium, color = Ws.text)
}

@Composable
private fun QueueRow(c: Controller, song: SongItem, index: Int, current: Boolean) {
    val accent = LocalAccent.current
    val recommended = c.nowQueue.isRecommended(index)
    HoverRow({ c.jumpTo(index) }, menu = { close -> SongMenuItems(c, song, c.queueContext, close, queueIndex = index) }) { hovered ->
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Thumb(song.thumbnail, 44.dp, corner = 4.dp, seed = song.title)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (recommended) Icon(Icons.Rounded.AutoAwesome, "Smart Shuffle recommendation", Modifier.size(14.dp), tint = accent.highlight)
                    Text(song.title, style = MaterialTheme.typography.bodyLarge, color = if (current) accent.highlight else Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(song.artists.joinToString { it.name }, style = MaterialTheme.typography.bodySmall, color = Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (recommended && !current) {
                if (c.canKeepRecommendations) WsIconButton(Icons.Rounded.AddCircleOutline, "Add to this playlist", { c.addRecommendationToPlaylist(index) }, size = 28.dp, iconSize = 18.dp, tint = Ws.text)
                WsIconButton(Icons.Rounded.RemoveCircleOutline, "Remove recommendation", { c.removeRecommendation(index) }, size = 28.dp, iconSize = 18.dp, tint = Ws.text)
            } else if (current) EqBars(c.nowPlayback.playing)
            else WsIconButton(
                Icons.Rounded.Close, "Remove from queue", { c.removeFromQueue(index) }, size = 28.dp, iconSize = 16.dp,
                tint = if (hovered) Ws.muted else androidx.compose.ui.graphics.Color.Transparent,
            )
        }
    }
}
