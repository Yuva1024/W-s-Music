package echopc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Bottom bar on the black window: now playing (left), transport + seek (centre), panels + volume (right). */
@Composable
fun PlayerBar(c: Controller) {
    val accent = LocalAccent.current
    val song = c.nowQueue.current
    val has = song != null
    // The bar is at the bottom of the window: its tooltips open upward.
    CompositionLocalProvider(LocalTooltipAbove provides true) { Row(
        Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ---- now playing ----
        Row(Modifier.weight(0.3f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (has) Thumb(song?.thumbnail, 56.dp, corner = 4.dp, seed = song?.title ?: "W")
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (song != null) {
                    Text(
                        song.title, Modifier.clickable { c.togglePanel(PanelTab.NOW_PLAYING) }.handCursor(),
                        style = MaterialTheme.typography.titleSmall, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                val err = c.nowPlayback.error
                when {
                    err != null -> Text("Couldn't play this song: ${playbackErrorText(err)}", style = MaterialTheme.typography.bodySmall, color = Ws.danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    song != null -> Text(song.artists.joinToString { it.name }, style = MaterialTheme.typography.bodySmall, color = Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                c.playingOn?.let { Text("Playing on $it", style = MaterialTheme.typography.labelSmall, color = accent.highlight, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (song != null) {
                val liked = song.id in c.likedIds
                WsIconButton(
                    if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Remove from Liked Songs" else "Save to Liked Songs", { c.toggleLike(song) },
                    size = 32.dp, iconSize = 18.dp, tint = if (liked) accent.highlight else Ws.muted, hoverTint = if (liked) accent.primary else Ws.text,
                    shortcut = Shortcut.LIKE.keys,
                )
            }
        }

        // ---- transport ----
        Column(Modifier.weight(0.4f).widthIn(max = 720.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val mode = c.shuffleMode()
                ShuffleButton(mode, shuffleActionLabel(mode, c.smartAvailable(c.queueContext)), { c.cycleShuffle() }, enabled = c.nowQueue.items.size > 1, shortcut = Shortcut.SHUFFLE.keys)
                WsIconButton(Icons.Rounded.SkipPrevious, "Previous", { c.skipPrevious() }, enabled = has, size = 32.dp, iconSize = 24.dp, shortcut = Shortcut.PREVIOUS.keys)
                PlayButton(c.nowPlayback.playing, c.nowPlayback.loading, has && c.nowPlayback.durationMs > 0 || c.nowPlayback.loading, { c.togglePause() }, size = 32.dp, shortcut = Shortcut.PLAY_PAUSE.keys)
                WsIconButton(Icons.Rounded.SkipNext, "Next", { c.skipNext() }, enabled = has, size = 32.dp, iconSize = 24.dp, shortcut = Shortcut.NEXT.keys)
                WsIconButton(
                    if (c.nowQueue.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    // What a click does next, like the shuffle button.
                    when (c.nowQueue.repeat) { RepeatMode.OFF -> "Turn on repeat"; RepeatMode.ALL -> "Repeat this song"; RepeatMode.ONE -> "Turn off repeat" },
                    { c.cycleRepeat() }, size = 32.dp, iconSize = 18.dp,
                    tint = if (c.nowQueue.repeat == RepeatMode.OFF) Ws.muted else accent.highlight,
                    shortcut = Shortcut.REPEAT.keys,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Only these two small widgets read the fast-changing position.
                ElapsedLabel(c)
                SeekBar(
                    value = { if (c.nowPlayback.durationMs > 0) c.nowPositionMs.toFloat() / c.nowPlayback.durationMs else 0f },
                    onChange = {}, onChangeFinished = { c.seek((it * c.nowPlayback.durationMs).toLong()) },
                    enabled = c.nowPlayback.durationMs > 0, modifier = Modifier.weight(1f),
                )
                Text(fmtTime(c.nowPlayback.durationMs), style = MaterialTheme.typography.labelSmall, color = Ws.muted, modifier = Modifier.width(40.dp))
            }
        }

        // ---- panels & volume ----
        Row(Modifier.weight(0.3f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            fun tintFor(on: Boolean) = if (on) accent.highlight else Ws.muted
            WsIconButton(Icons.Rounded.AutoAwesome, if (c.autoplay) "Autoplay similar songs: on" else "Autoplay similar songs: off", { c.autoplay = !c.autoplay }, size = 32.dp, iconSize = 18.dp, tint = tintFor(c.autoplay))
            WsIconButton(Icons.Rounded.ViewSidebar, "Now playing view", { c.togglePanel(PanelTab.NOW_PLAYING) }, size = 32.dp, iconSize = 18.dp, tint = tintFor(c.panelOpen && c.panelTab == PanelTab.NOW_PLAYING))
            WsIconButton(Icons.Rounded.Lyrics, "Lyrics", { c.toggleLyrics() }, size = 32.dp, iconSize = 18.dp, tint = tintFor(c.route == Route.Lyrics), enabled = has)
            WsIconButton(Icons.Rounded.QueueMusic, "Queue", { c.togglePanel(PanelTab.QUEUE) }, size = 32.dp, iconSize = 18.dp, tint = tintFor(c.panelOpen && c.panelTab == PanelTab.QUEUE))
            VoiceDuckButton(c)
            DevicesButton(c)
            val volIcon = when { c.nowVolume == 0f -> Icons.Rounded.VolumeOff; c.nowVolume < 0.5f -> Icons.Rounded.VolumeDown; else -> Icons.Rounded.VolumeUp }
            WsIconButton(volIcon, if (c.nowVolume == 0f) "Unmute" else "Mute", { c.toggleMute() }, size = 32.dp, iconSize = 18.dp, shortcut = Shortcut.MUTE.keys)
            SeekBar({ c.nowVolume }, { c.changeVolume(it) }, Modifier.width(96.dp))
        }
    } }
}

/** Own composable so only this label (not the whole player bar) recomposes as time advances. */
@Composable
private fun ElapsedLabel(c: Controller) {
    Text(fmtTime(c.nowPositionMs), style = MaterialTheme.typography.labelSmall, color = Ws.muted, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
}
