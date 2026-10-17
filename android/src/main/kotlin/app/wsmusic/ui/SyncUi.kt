package app.wsmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import echopc.Controller
import echopc.sync.PhonePairing
import echopc.sync.SyncCategory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** "Sync with computer": find and pair the computer, then the two switches, Sync now and Unpair. */
@Composable
fun SyncDialog(c: Controller) {
    val s = c.sync
    val pc = s.peers.firstOrNull { it.role == "pc" }
    fun close() { if (s.pairing != null) s.cancelPairing(); c.syncOpen = false }
    LaunchedEffect(pc == null) { if (pc == null && s.pairing == null) s.search() }
    AlertDialog(
        onDismissRequest = ::close, containerColor = Ws.surfaceHi,
        title = { Text("Sync with computer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (pc != null) {
                    Text("Paired with ${pc.name}", color = Ws.text, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (pc.lastSync > 0) "Last synced ${time(pc.lastSync)}. Syncs by itself on the same Wi-Fi while W's MUSIC is open on both." else "Not synced yet.",
                        color = Ws.muted, style = MaterialTheme.typography.bodySmall,
                    )
                    Toggle(c, "Sync library", "Liked songs and your playlists", s.syncLibrary) { s.setSwitch(SyncCategory.LIBRARY, it) }
                    Toggle(c, "Sync music taste", "History, plays and skips, \"not interested\", picked artists", s.syncTaste) { s.setSwitch(SyncCategory.TASTE, it) }
                    if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    s.status?.let { Text(it, color = Ws.muted, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ s.syncNow() }, enabled = !s.busy) { Text("Sync now", color = Ws.text) }
                        TextButton({ s.unpair(pc.id) }) { Text("Unpair", color = Ws.danger) }
                    }
                } else {
                    PairingSteps(c)
                }
            }
        },
        confirmButton = { TextButton(::close) { Text("Close") } },
    )
}

@Composable
private fun PairingSteps(c: Controller) {
    val s = c.sync
    when (val p = s.pairing) {
        null -> {
            Text("Share likes, playlists and music taste with W's MUSIC on your computer, over your home Wi-Fi. No account needed.", color = Ws.muted)
            Text("On the computer, click the Sync button at the top, then \"Pair a phone\". Then choose it here:", color = Ws.muted, style = MaterialTheme.typography.bodySmall)
            if (s.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            s.found.forEach { found ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Ws.surfaceTop).clickable { s.pairWith(found) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(found.name, color = Ws.text, style = MaterialTheme.typography.bodyLarge)
                        Text(found.host, color = Ws.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Pair", color = c.accent.highlight)
                }
            }
            if (!s.searching && s.found.isEmpty()) Text("No computer found yet. Make sure both are on the same Wi-Fi and W's MUSIC is open on the computer.", color = Ws.muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ s.search() }, enabled = !s.searching) { Text("Search again", color = Ws.text) }
            var address by remember { mutableStateOf("") }
            Text("Or type the address the computer shows:", color = Ws.muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("192.168.1.20:47811") })
            OutlinedButton({ s.manualPc(address)?.let { s.pairWith(it) } }, enabled = address.isNotBlank()) { Text("Connect", color = Ws.text) }
        }
        is PhonePairing.Connecting -> {
            Text("Connecting to ${p.pc.name}…", color = Ws.text)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is PhonePairing.Confirm -> {
            Text("Check that ${p.pc.name} shows this code:", color = Ws.muted)
            Text(p.code, color = c.accent.highlight, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ s.confirmCode() }) { Text("Codes match", color = Ws.text) }
                TextButton({ s.cancelPairing() }) { Text("Cancel") }
            }
        }
        is PhonePairing.WaitingForPc -> {
            Text("Now click Allow on ${p.pc.name}.", color = Ws.text)
            Text(p.code, color = Ws.muted, style = MaterialTheme.typography.titleLarge)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton({ s.cancelPairing() }) { Text("Cancel") }
        }
        is PhonePairing.Failed -> {
            Text(p.message, color = Ws.danger)
            OutlinedButton({ s.cancelPairing(); s.search() }) { Text("Try again", color = Ws.text) }
        }
    }
}

@Composable
private fun Toggle(c: Controller, title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ws.text, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = Ws.muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent.primary, checkedThumbColor = c.accent.onPrimary))
    }
}

private fun time(millis: Long): String {
    val t = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDateTime()
    return if (t.toLocalDate() == LocalDate.now()) "today at " + t.format(DateTimeFormatter.ofPattern("HH:mm")) else t.format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
}

/** "Play on": this phone or the paired computer, like Spotify Connect. */
@Composable
fun DevicesDialog(c: Controller, onClose: () -> Unit) {
    val other = c.sync.remote.other?.takeIf { c.sync.remote.connected }
    val controlling = c.remoteControlling
    AlertDialog(
        onDismissRequest = onClose, containerColor = Ws.surfaceHi,
        title = { Text("Play on") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DeviceChoice(c, "This phone", if (!controlling && c.playback.playing) "Playing" else "", selected = !controlling) {
                    if (controlling) c.playHere()
                    onClose()
                }
                if (other != null) {
                    DeviceChoice(c, other.name, when { other.playing -> "Playing " + (other.current?.title ?: ""); other.current != null -> "Paused"; else -> "Ready" }, selected = controlling) {
                        if (!controlling) { if (c.queue.current != null) c.playOnOtherDevice() else c.showToast("Play something first, then move it to ${other.name}") }
                        onClose()
                    }
                } else {
                    Text(
                        if (c.sync.peers.none { it.role == "pc" }) "Pair your computer (Your Library › Sync with computer) to play music there from this phone."
                        else "Your computer isn't connected. Open W's MUSIC on it, on the same Wi-Fi.",
                        color = Ws.muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } },
    )
}

@Composable
private fun DeviceChoice(c: Controller, name: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected) Ws.surfaceTop else Ws.surfaceHi).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.material3.Icon(WsIcons.Devices, null, tint = if (selected) c.accent.highlight else Ws.text)
        Column(Modifier.weight(1f)) {
            Text(name, color = if (selected) c.accent.highlight else Ws.text, style = MaterialTheme.typography.bodyLarge)
            if (detail.isNotBlank()) Text(detail, color = Ws.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}
