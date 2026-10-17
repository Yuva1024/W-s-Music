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
import androidx.compose.runtime.Composable
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
