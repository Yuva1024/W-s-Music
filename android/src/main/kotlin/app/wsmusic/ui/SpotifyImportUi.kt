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
    AlertDialog(
        onDismissRequest = ::close, containerColor = Ws.surfaceHi,
        title = { Text("Import from Spotify") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paste a playlist or album link. In Spotify: ⋮ › Share › Copy link, or share it straight to W's MUSIC. The songs become a playlist here and shape your taste and mixes.", color = Ws.muted)
                OutlinedTextField(c.spotifyImportDraft, { c.spotifyImportDraft = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("https://open.spotify.com/playlist/…") })
                Text("All playlists and liked songs: on spotify.com go to Account › Privacy settings › Download your data and tick \"Account data\". For full listening history also tick \"Extended streaming history\". Choose each .zip Spotify emails you.",
                    color = Ws.muted, style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ close(); pickFile.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "text/plain", "*/*")) }) {
                    Text("Choose Spotify data file", color = Ws.text)
                }
            }
        },
        confirmButton = { TextButton({ val link = c.spotifyImportDraft; close(); c.importSpotifyLink(link) }, enabled = SpotifyLink.looksLikeLink(c.spotifyImportDraft)) { Text("Import") } },
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
