package echopc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.HeadsetMic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import echopc.sync.SyncCategory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** "Sync with your phone": pairing, paired phones and the two switches. Opens by itself when a phone asks to pair. */
@Composable
fun SyncDialog(c: Controller) {
    val s = c.sync
    AlertDialog(
        onDismissRequest = { c.syncOpen = false }, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.width(520.dp),
        title = { Text("Sync with your phone", style = MaterialTheme.typography.headlineMedium, color = Ws.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Your phone and this computer share likes, playlists and music taste over your home Wi-Fi. No account, and nothing leaves your network.",
                    style = MaterialTheme.typography.bodyMedium, color = Ws.muted,
                )
                SyncSwitch(c, "Sync library", "Liked songs and your playlists", s.syncLibrary) { s.setSwitch(SyncCategory.LIBRARY, it) }
                SyncSwitch(c, "Sync music taste", "Listening history, plays and skips, \"not interested\", picked artists", s.syncTaste) { s.setSwitch(SyncCategory.TASTE, it) }

                s.pairRequest?.let { r ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Ws.surfaceTop).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("${r.phoneName} wants to sync", style = MaterialTheme.typography.titleMedium, color = Ws.text)
                        Text(r.code, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = c.accent.highlight)
                        Text("Allow only if your phone shows the same code.", style = MaterialTheme.typography.bodySmall, color = Ws.muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton("Allow", { s.allowPair() })
                            OutlineButton("Deny", { s.denyPair() })
                        }
                    }
                }

                if (s.peers.isNotEmpty()) {
                    Text("Paired", style = MaterialTheme.typography.titleSmall, color = Ws.text)
                    s.peers.forEach { p ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = Ws.text, style = MaterialTheme.typography.bodyLarge)
                                Text(if (p.lastSync > 0) "Last synced ${syncTime(p.lastSync)}" else "Not synced yet. Open W's MUSIC on the phone.", color = Ws.muted, style = MaterialTheme.typography.bodySmall)
                            }
                            OutlineButton("Unpair", { s.unpair(p.id) })
                        }
                    }
                }

                s.status?.let { Text(it, color = Ws.muted, style = MaterialTheme.typography.bodySmall) }
                s.serverError?.let { Text(it, color = Ws.danger, style = MaterialTheme.typography.bodySmall) }

                if (s.pairingOpen && s.pairRequest == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Waiting for your phone…", style = MaterialTheme.typography.titleSmall, color = Ws.text)
                        Text("On the phone: Your Library › Sync with computer, then choose ${AppHooks.deviceName()}.", style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
                        if (s.addresses.isNotEmpty()) Text(
                            "If it doesn't show up, type this address on the phone: " + s.addresses.joinToString(" or ") { "$it:${s.port}" },
                            style = MaterialTheme.typography.bodySmall, color = Ws.muted,
                        )
                        Text("Both must be on the same Wi-Fi. If Windows asks, allow W's MUSIC on private networks.", style = MaterialTheme.typography.bodySmall, color = Ws.faint)
                    }
                }
            }
        },
        confirmButton = {
            when {
                s.pairingOpen -> OutlineButton("Stop pairing", { s.closePairing() })
                else -> PrimaryButton(if (s.peers.isEmpty()) "Pair a phone" else "Pair another phone", { s.openPairing() })
            }
        },
        dismissButton = { OutlineButton("Close", { c.syncOpen = false }) },
    )
}

@Composable
private fun SyncSwitch(c: Controller, title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ws.text, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = Ws.muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent.primary, checkedThumbColor = c.accent.onPrimary))
    }
}

internal fun syncTime(millis: Long): String {
    val t = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDateTime()
    val today = java.time.LocalDate.now()
    return if (t.toLocalDate() == today) "today at " + t.format(DateTimeFormatter.ofPattern("HH:mm")) else t.format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
}

/**
 * "Devices": where the music plays, like Spotify Connect. Picking the phone moves the queue and position there;
 * picking this computer takes it back. Highlighted while the phone is the one playing.
 */
@Composable
fun DevicesButton(c: Controller) {
    var open by remember { mutableStateOf(false) }
    val other = c.sync.remote.other?.takeIf { c.sync.remote.connected }
    val controlling = c.remoteControlling
    androidx.compose.foundation.layout.Box {
        WsIconButton(
            Icons.Rounded.Devices, if (controlling) "Playing on ${other?.name}" else "Connect to a device",
            { open = true }, size = 32.dp, iconSize = 18.dp, tint = if (controlling) c.accent.highlight else Ws.muted,
        )
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = Ws.surfaceTop) {
            Text("Play on", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = Ws.muted)
            DeviceRow(c, "This computer", if (controlling) "" else if (c.playback.playing) "Playing" else "", selected = !controlling) {
                open = false
                if (controlling) c.playHere()
            }
            if (other != null) {
                DeviceRow(c, other.name, when { other.playing -> "Playing " + (other.current?.title ?: ""); other.current != null -> "Paused"; else -> "Ready" }, selected = controlling) {
                    open = false
                    if (!controlling) { if (c.queue.current != null) c.playOnOtherDevice() else c.showToast("Play something first, then move it to ${other.name}") }
                }
            } else {
                Text(
                    if (c.sync.peers.isEmpty()) "Pair your phone in Sync to play there" else "Your phone isn't connected. Open W's MUSIC on it (same Wi-Fi).",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(240.dp), style = MaterialTheme.typography.bodySmall, color = Ws.muted,
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(c: Controller, name: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = {
            Column {
                Text(name, color = if (selected) c.accent.highlight else Ws.text, style = MaterialTheme.typography.bodyMedium)
                if (detail.isNotBlank()) Text(detail, color = Ws.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        },
        onClick = onClick,
    )
}

/**
 * Discord mode: lower or pause the music only while someone is talking in Discord (you can stay in the call).
 * Click to choose; lit while on, brighter while someone talks.
 */
@Composable
fun VoiceDuckButton(c: Controller) {
    var open by remember { mutableStateOf(false) }
    val on = c.voiceDuck != VoiceDuck.OFF
    androidx.compose.foundation.layout.Box {
        WsIconButton(
            Icons.Rounded.HeadsetMic,
            when (c.voiceDuck) {
                VoiceDuck.OFF -> "Discord mode: off"
                VoiceDuck.LOWER -> if (c.voiceTalking) "Discord mode: someone is talking, music lowered" else "Discord mode: lowers the music while someone talks"
                VoiceDuck.PAUSE -> if (c.voiceTalking) "Discord mode: someone is talking, music paused" else "Discord mode: pauses the music while someone talks"
            },
            { open = true }, size = 32.dp, iconSize = 18.dp,
            tint = when { on && c.voiceTalking -> Ws.text; on -> c.accent.highlight; else -> Ws.muted },
        )
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = Ws.surfaceTop) {
            Text("Discord mode", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = Ws.muted)
            Text(
                "Only while someone talks in Discord. You stay in the call; your own voice doesn't count.",
                Modifier.padding(horizontal = 16.dp).width(260.dp), style = MaterialTheme.typography.bodySmall, color = Ws.muted,
            )
            listOf(
                VoiceDuck.OFF to "Off",
                VoiceDuck.LOWER to "Lower the music",
                VoiceDuck.PAUSE to "Pause the music",
            ).forEach { (mode, label) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label, color = if (c.voiceDuck == mode) c.accent.highlight else Ws.text) },
                    onClick = { c.changeVoiceDuck(mode); open = false },
                )
            }
        }
    }
}
