package app.wsmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import echopc.Controller
import echopc.RepeatMode
import echopc.Route
import echopc.ShuffleMode
import echopc.playbackErrorText

enum class Tab { Home, Search, Library }

/** The phone layout: page, mini player and bottom tabs; Now Playing slides up over everything. */
@Composable
fun PhoneApp(c: Controller) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var nowPlaying by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = nowPlaying) { nowPlaying = false }
    BackHandler(enabled = !nowPlaying && c.canGoBack) { c.goBack() }

    Box(Modifier.fillMaxSize().background(Ws.bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).windowInsetsPadding(WindowInsets.statusBars)) {
                when (val r = c.route) {
                    Route.Home -> if (tab == Tab.Library) LibraryScreen(c) else HomeScreen(c)
                    Route.Search -> SearchScreen(c)
                    Route.Liked -> LikedScreen(c)
                    Route.LibrarySongs -> LibrarySongsScreen(c)
                    Route.History -> HistoryScreen(c)
                    is Route.LocalPlaylist -> LocalPlaylistScreen(c, r.id)
                    is Route.Mix -> MixScreen(c, r.id)
                    is Route.Online -> OnlineScreen(c, r.key)
                    Route.Picker -> PickerScreen(c)
                    Route.Downloads -> DownloadsScreen(c)
                    Route.LocalFiles -> LocalFilesScreen(c)
                    Route.Lyrics -> { nowPlaying = true; c.goBack() }
                }
            }
            c.toast?.let { msg ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, Modifier.weight(1f), color = Color.Black, style = MaterialTheme.typography.bodyMedium)
                    c.toastAction?.let { a -> Text(a.label, Modifier.clickable { c.runToastAction() }.padding(start = 12.dp), color = Color.Black, fontWeight = FontWeight.Bold) }
                }
            }
            if (c.nowQueue.current != null) MiniPlayer(c) { nowPlaying = true }
            NavigationBar(containerColor = Color.Black, modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(64.dp)) {
                val colors = NavigationBarItemDefaults.colors(selectedIconColor = Ws.text, selectedTextColor = Ws.text, unselectedIconColor = Ws.faint, unselectedTextColor = Ws.faint, indicatorColor = Color.Transparent)
                fun go(t: Tab) {
                    tab = t
                    when (t) { Tab.Search -> c.navigate(Route.Search); else -> c.goHome() }
                }
                val onHome = c.route == Route.Home
                NavigationBarItem(onHome && tab == Tab.Home, { go(Tab.Home) }, { Icon(Icons.Filled.Home, null) }, label = { Text("Home") }, colors = colors)
                NavigationBarItem(c.route == Route.Search, { go(Tab.Search) }, { Icon(Icons.Filled.Search, null) }, label = { Text("Search") }, colors = colors)
                NavigationBarItem(onHome && tab == Tab.Library, { go(Tab.Library) }, { Icon(WsIcons.Library, null) }, label = { Text("Your Library") }, colors = colors)
            }
        }
        AnimatedVisibility(nowPlaying, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            NowPlaying(c) { nowPlaying = false }
        }
        if (c.newPlaylistOpen) NameDialog("New playlist", "", "Create", { c.createPlaylist(it) }, { c.newPlaylistOpen = false; c.newPlaylistFor = null })
        if (c.updateDialogOpen) UpdateDialog(c)
        if (c.spotifyImportOpen) SpotifyImportDialog(c)
        if (c.syncOpen) SyncDialog(c)
        ImportProgressDialog(c)
    }
}

@Composable
private fun MiniPlayer(c: Controller, onOpen: () -> Unit) {
    val song = c.nowQueue.current ?: return
    val accent = c.accent
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp))
            .background(lerp(accent.primary, Color.Black, 0.55f)).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Art(song.thumbnail, 42.dp, corner = 4.dp)
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ws.text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                val err = c.nowPlayback.error
                Text(
                    when { err != null -> playbackErrorText(err); c.nowPlayback.loading -> "Loading…"; else -> song.artists.joinToString { it.name } },
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (err != null) Ws.danger else Ws.muted, style = MaterialTheme.typography.bodySmall,
                )
            }
            val liked = song.id in c.likedIds
            IconButton({ c.toggleLike(song) }) { Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (liked) "Remove from Liked Songs" else "Save to Liked Songs", tint = if (liked) accent.highlight else Ws.text) }
            IconButton({ c.togglePause() }) { Icon(if (c.nowPlayback.playing) WsIcons.Pause else Icons.Filled.PlayArrow, if (c.nowPlayback.playing) "Pause" else "Play", tint = Ws.text) }
        }
        val d = c.nowPlayback.durationMs
        LinearProgressIndicator(
            progress = { if (d > 0) (c.nowPositionMs.toFloat() / d).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(2.dp), color = Ws.text, trackColor = Color.White.copy(alpha = 0.2f), drawStopIndicator = {},
        )
        c.playingOn?.let { name ->
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(WsIcons.Devices, null, tint = accent.highlight, modifier = Modifier.size(14.dp))
                Text("Playing on $name", color = accent.highlight, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun NowPlaying(c: Controller, onClose: () -> Unit) {
    val song = c.nowQueue.current
    val accent = c.accent
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerp(accent.primary, Color.Black, 0.35f), Ws.bg)))
            .clickable(enabled = false) {}.windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Close", tint = Ws.text) }
            Text("Now playing", Modifier.weight(1f), color = Ws.text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            var devices by remember { mutableStateOf(false) }
            IconButton({ devices = true }) { Icon(WsIcons.Devices, "Devices", tint = if (c.playingOn != null) accent.highlight else Ws.text) }
            if (devices) DevicesDialog(c) { devices = false }
        }
        if (song == null) { Message("Nothing is playing."); return@Column }
        val reorder = remember { ReorderState() }
        LazyColumn(Modifier.weight(1f)) {
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Art(song.thumbnail, 300.dp, corner = 8.dp) }
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = Ws.text, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(song.artists.joinToString { it.name }, color = Ws.muted, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val liked = song.id in c.likedIds
                    IconButton({ c.toggleLike(song) }) { Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (liked) "Remove from Liked Songs" else "Save to Liked Songs", tint = if (liked) accent.highlight else Ws.text, modifier = Modifier.size(28.dp)) }
                }
                SeekBar(c)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    val mode = c.shuffleMode()
                    IconButton({ c.cycleShuffle() }) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(WsIcons.Shuffle, "Shuffle", tint = if (mode == ShuffleMode.OFF) Ws.text else accent.highlight)
                            if (mode == ShuffleMode.SMART) Text("✦", Modifier.align(Alignment.TopEnd), color = accent.highlight, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    IconButton({ c.skipPrevious() }) { Icon(WsIcons.SkipPrevious, "Previous", tint = Ws.text, modifier = Modifier.size(36.dp)) }
                    Box(Modifier.size(68.dp).clip(RoundedCornerShape(50)).background(Color.White).clickable { c.togglePause() }, contentAlignment = Alignment.Center) {
                        Icon(if (c.nowPlayback.playing) WsIcons.Pause else Icons.Filled.PlayArrow, if (c.nowPlayback.playing) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(36.dp))
                    }
                    IconButton({ c.skipNext() }) { Icon(WsIcons.SkipNext, "Next", tint = Ws.text, modifier = Modifier.size(36.dp)) }
                    IconButton({ c.cycleRepeat() }) {
                        Icon(if (c.nowQueue.repeat == RepeatMode.ONE) WsIcons.RepeatOne else WsIcons.Repeat, "Repeat", tint = if (c.nowQueue.repeat == RepeatMode.OFF) Ws.text else accent.highlight)
                    }
                }
                c.playingOn?.let { Text("Playing on $it", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = accent.highlight) }
                c.nowPlayback.error?.let { Text(playbackErrorText(it), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = Ws.danger) }
                if (c.nowPlayback.loading) Text("Loading…", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = Ws.muted)
                SectionTitle("Up next")
            }
            val items = c.nowQueue.items
            val start = c.nowQueue.index + 1
            // Up next: drag the grip to reorder, swipe left to remove.
            val shown = items.drop(start).take(100)
            val last = start + shown.lastIndex
            itemsIndexed(shown, key = { i, s -> "next-${start + i}-${s.id}" }) { i, s ->
                val at = start + i
                Box(reorder.row(at, start, last)) {
                    Swipeable(right = null, left = SwipeAction("Remove", WsIcons.Close, Color(0xFFD32F2F)) { c.removeFromQueue(at) }) {
                        QueueItem(s, { c.jumpTo(at) }, reorder.handle(at, start, last) { from, to -> c.moveInQueue(from, to) })
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SeekBar(c: Controller) {
    val duration = c.nowPlayback.durationMs
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragValue else if (duration > 0) (c.nowPositionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(horizontal = 16.dp)) {
        Slider(
            value = shown,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { if (duration > 0) c.seek((dragValue * duration).toLong()); dragging = false },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(fmtTime((shown * duration).toLong()), color = Ws.muted, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(fmtTime(duration), color = Ws.muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun UpdateDialog(c: Controller) {
    val info = c.update ?: return
    val busy = c.updateProgress != null
    AlertDialog(
        onDismissRequest = { if (!busy) c.updateDialogOpen = false }, containerColor = Ws.surfaceHi,
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Version ${info.version} is ready. Your library, likes and settings are kept. Android asks you to tap Update once" + if (android.os.Build.VERSION.SDK_INT < 29) "; then W's MUSIC reopens by itself." else ", then open W's MUSIC again.")
                if (info.notes.isNotBlank()) Text(info.notes.take(300), color = Ws.muted, style = MaterialTheme.typography.bodySmall)
                c.updateProgress?.let { p -> LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth()); Text("Downloading… ${(p * 100).toInt()}%", color = Ws.muted) }
                c.updateError?.let { Text("Update failed: $it", color = Ws.danger) }
            }
        },
        confirmButton = { TextButton({ c.installUpdate() }, enabled = !busy) { Text(if (c.updateError != null) "Try again" else "Update now") } },
        dismissButton = { TextButton({ c.updateDialogOpen = false }, enabled = !busy) { Text("Not now") } },
    )
}

/** A song in "Up next", with a grip to drag it to another place. */
@Composable
private fun QueueItem(song: com.music.innertube.models.SongItem, onClick: () -> Unit, grip: Modifier) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Art(song.thumbnail, 44.dp)
        Column(Modifier.weight(1f)) {
            Text(song.title, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(song.artists.joinToString { it.name }, color = Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
        Box(grip.size(48.dp), contentAlignment = Alignment.Center) { Icon(WsIcons.DragHandle, "Drag to reorder", tint = Ws.muted) }
    }
}
