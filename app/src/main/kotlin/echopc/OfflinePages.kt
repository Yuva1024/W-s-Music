package echopc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.innertube.models.SongItem
import java.io.File

/** Songs saved for offline listening. */
@Composable
fun DownloadsPage(c: Controller) {
    val songs = c.downloads.downloadedSongs
    val waiting = c.downloads.active.size
    CollectionPage(
        c, "Offline", "Downloads", "${songs.size} songs" + if (waiting > 0) " • $waiting downloading" else "",
        CoverArt.Icon(Icons.Rounded.DownloadDone), songs,
        emptyText = "Songs you download play without internet. Use the download button on a playlist or album, or \"Download\" in a song's menu.",
        accent = Accent(Color(0xFF1E6F5C), Color(0xFF29BB89)),
        rowTrailing = { i -> WsIconButton(Icons.Rounded.Close, "Remove download", { c.downloads.remove(songs[i]) }, size = 32.dp, iconSize = 16.dp) },
        actions = { if (waiting > 0) OutlineButton("Cancel downloads", { c.downloads.cancelAll() }) },
    )
}

/** Music files on this PC, from the chosen folders. */
@Composable
fun LocalFilesPage(c: Controller, onAddFolder: () -> Unit) {
    LaunchedEffect(Unit) { if (c.localSongs == null) c.scanLocalMusic() }
    var foldersOpen by remember { mutableStateOf(false) }
    val songs = c.localSongs.orEmpty()
    val folders = DesktopLocalMusic.folders
    CollectionPage(
        c, "On this PC", "Local files",
        when {
            c.localScanning -> "Looking for music…"
            else -> "${songs.size} songs from " + folders.joinToString { it.name }.ifEmpty { "no folders yet" }
        },
        CoverArt.Icon(Icons.Rounded.Folder), songs,
        emptyText = c.localError ?: if (c.localScanning) "Looking for music…" else "No music files found. Add a folder with MP3, M4A or WAV files.",
        accent = Accent(Color(0xFF5B4B8A), Color(0xFF8E7CC3)),
        actions = {
            OutlineButton("Add folder", onAddFolder)
            OutlineButton("Folders", { foldersOpen = true })
            OutlineButton(if (c.localScanning) "Scanning…" else "Rescan", { c.scanLocalMusic() }, enabled = !c.localScanning)
        },
    )
    if (foldersOpen) AlertDialog(
        onDismissRequest = { foldersOpen = false }, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
        title = { Text("Music folders", style = MaterialTheme.typography.headlineMedium, color = Ws.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (folders.isEmpty()) Text("No folders yet.", color = Ws.muted)
                folders.forEach { f ->
                    Row(Modifier.fillMaxWidth().width(460.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.absolutePath, Modifier.weight(1f), color = Ws.text, style = MaterialTheme.typography.bodyMedium)
                        WsIconButton(Icons.Rounded.Close, "Stop using this folder", { DesktopLocalMusic.removeFolder(f); c.scanLocalMusic() }, size = 32.dp, iconSize = 16.dp)
                    }
                }
            }
        },
        confirmButton = { PrimaryButton("Add folder", { foldersOpen = false; onAddFolder() }) },
        dismissButton = { OutlineButton("Close", { foldersOpen = false }) },
    )
}

/** Folder picker for "Add folder" (Swing's, with the Windows look). */
fun pickMusicFolder(): File? = runCatching {
    javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
    val chooser = javax.swing.JFileChooser(DesktopLocalMusic.folders.firstOrNull() ?: File(System.getProperty("user.home"))).apply {
        dialogTitle = "Choose a folder with music"
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    }
    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}.getOrNull()

/** A playlist's download button: download all / downloading / downloaded. */
@Composable
fun DownloadAllButton(c: Controller, songs: List<SongItem>) {
    val online = songs.filter { !OfflineFiles.isLocal(it.id) }
    if (online.isEmpty()) return
    val done = online.count { c.downloads.isDownloaded(it.id) }
    val busy = online.count { it.id in c.downloads.active }
    when {
        busy > 0 -> WsIconButton(Icons.Rounded.Downloading, "Downloading ($done of ${online.size} saved)", {}, size = 40.dp, iconSize = 26.dp, tint = LocalAccent.current.highlight)
        done == online.size -> WsIconButton(Icons.Rounded.DownloadDone, "Downloaded: plays without internet", {}, size = 40.dp, iconSize = 26.dp, tint = LocalAccent.current.highlight)
        else -> WsIconButton(Icons.Rounded.Download, if (done > 0) "Download the other ${online.size - done} songs" else "Download (${online.size} songs)", { c.downloads.download(online) }, size = 40.dp, iconSize = 26.dp)
    }
}
