package app.wsmusic.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import echopc.Controller
import echopc.ImportUi
import echopc.SpotifyLink

/** Paste (or share from Spotify) a playlist or album link, or choose the Spotify data download. */
@Composable
fun SpotifyImportDialog(c: Controller) {
    val context = LocalContext.current
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Spotify data"
            c.importSpotifyFile(name) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("The file couldn't be opened.") }
        }
    }
    fun close() { c.spotifyImportOpen = false; c.spotifyImportDraft = "" }
    var asLiked by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var name by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("From Spotify") }
    val songs = androidx.compose.runtime.remember(c.spotifyImportDraft) { SpotifyLink.trackIds(c.spotifyImportDraft).size }
    val songList = songs > 1 || (songs == 1 && SpotifyLink.parse(c.spotifyImportDraft)?.kind == "track")
    AlertDialog(
        onDismissRequest = ::close, containerColor = Ws.surfaceHi,
        title = { Text("Import from Spotify") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paste a playlist or album link. In Spotify: ⋮ › Share › Copy link, or share it straight to W's MUSIC. The songs become a playlist here and shape your taste and mixes.", color = Ws.muted)
                OutlinedTextField(c.spotifyImportDraft, { c.spotifyImportDraft = it }, Modifier.fillMaxWidth(), maxLines = 4, placeholder = { Text("https://open.spotify.com/playlist/…") })
                Text("Liked Songs or a long playlist: on a computer, in the Spotify app open it, click a song, press Ctrl+A then Ctrl+C and paste in W's MUSIC there; with Sync on it comes to this phone too.",
                    color = Ws.muted, style = MaterialTheme.typography.bodySmall)
                if (songList) {
                    Text("$songs songs found. Add them to:", color = Ws.text)
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(asLiked, { asLiked = true }); Text("Liked Songs", color = Ws.text)
                        androidx.compose.material3.RadioButton(!asLiked, { asLiked = false }); Text("New playlist", color = Ws.text)
                    }
                    if (!asLiked) OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Playlist name") })
                }
                Text("Also possible: the data download from spotify.com (Account › Privacy settings › Download your data). \"Account data\" has all playlists and liked songs; \"Extended streaming history\" has only your plays.",
                    color = Ws.muted, style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ close(); pickFile.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "text/plain", "*/*")) }) {
                    Text("Choose Spotify data file", color = Ws.text)
                }
            }
        },
        confirmButton = {
            TextButton({
                val link = c.spotifyImportDraft
                close()
                if (songList) c.importSpotifySongs(link, asLiked, name) else c.importSpotifyLink(link)
            }, enabled = SpotifyLink.looksLikeLink(c.spotifyImportDraft)) { Text("Import") }
        },
        dismissButton = { TextButton(::close) { Text("Cancel") } },
    )
}

/** Progress and result of an import. */
@Composable
fun ImportProgressDialog(c: Controller) {
    when (val s = c.importUi) {
        ImportUi.Idle -> Unit
        is ImportUi.Running -> AlertDialog(
            onDismissRequest = {}, containerColor = Ws.surfaceHi,
            title = { Text("Importing") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.detail.ifEmpty { "Reading ${s.fileName}…" }, color = Ws.muted)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("About a minute for every 100 songs. Music keeps playing meanwhile.", color = Ws.faint, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { if (s.cancellable) TextButton({ c.cancelSpotifyImport() }) { Text("Stop") } },
        )
        is ImportUi.Message -> AlertDialog(
            onDismissRequest = { c.importUi = ImportUi.Idle }, containerColor = Ws.surfaceHi,
            title = { Text(s.title) },
            text = { Text(s.text, Modifier.verticalScroll(rememberScrollState()), color = Ws.muted) },
            confirmButton = { TextButton({ c.importUi = ImportUi.Idle }) { Text("Done") } },
        )
        is ImportUi.Done -> AlertDialog(
            onDismissRequest = { c.importUi = ImportUi.Idle }, containerColor = Ws.surfaceHi,
            title = { Text("Import complete") },
            text = { Text(s.result.summary(), color = Ws.muted) },
            confirmButton = { TextButton({ c.importUi = ImportUi.Idle }) { Text("Done") } },
        )
        is ImportUi.Failed -> AlertDialog(
            onDismissRequest = { c.importUi = ImportUi.Idle }, containerColor = Ws.surfaceHi,
            title = { Text("Import didn't work", color = Ws.danger) },
            text = { Text(s.message, color = Ws.muted) },
            confirmButton = { TextButton({ c.importUi = ImportUi.Idle }) { Text("Close") } },
        )
    }
}
