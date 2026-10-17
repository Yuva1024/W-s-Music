package echopc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private class LibraryEntry(val key: String, val route: Route, val title: String, val subtitle: String, val thumb: String?, val icon: ImageVector?, val iconTint: Color?)

/** "Your Library": pinned collections plus local playlists. Resizable, or collapsed to a strip of covers. */
@Composable
fun LibrarySidebar(c: Controller, onPickAndroidFile: () -> Unit) {
    val liked by c.library.likedSongs.collectAsState(emptyList())
    val librarySongs by c.library.librarySongs.collectAsState(emptyList())
    val playlists by c.library.playlists.collectAsState(emptyList())
    var filter by remember { mutableStateOf("") }
    var filtering by remember { mutableStateOf(false) }
    var alphabetical by remember { mutableStateOf(false) }
    val collapsed = c.sidebarCollapsed

    val entries = remember(liked.size, librarySongs.size, playlists, filter, alphabetical) {
        val pinned = listOf(
            LibraryEntry("liked", Route.Liked, "Liked Songs", "Playlist • ${liked.size} songs", null, Icons.Rounded.Favorite, null),
            LibraryEntry("songs", Route.LibrarySongs, "Your songs", "Collection • ${librarySongs.size} songs", null, Icons.Rounded.MusicNote, Ws.surfaceTop),
            LibraryEntry("history", Route.History, "Recently played", "History", null, Icons.Rounded.History, Ws.surfaceTop),
        )
        val own = playlists.map { p ->
            LibraryEntry("pl-${p.playlist.id}", Route.LocalPlaylist(p.playlist.id), p.playlist.name, "Playlist • ${p.songCount} songs", p.thumbnails.firstOrNull(), null, null)
        }.let { if (alphabetical) it.sortedBy { e -> e.title.lowercase() } else it }
        (pinned + own).filter { filter.isBlank() || it.title.contains(filter, ignoreCase = true) }
    }

    Column(Modifier.width(if (collapsed) 72.dp else c.sidebarWidth.dp).fillMaxHeight().panel()) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(start = if (collapsed) 0.dp else 16.dp, end = if (collapsed) 0.dp else 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.spacedBy(4.dp),
        ) {
            WsIconButton(Icons.Rounded.LibraryMusic, if (collapsed) "Expand Your Library" else "Collapse Your Library", { c.sidebarCollapsed = !collapsed }, size = 36.dp, iconSize = 24.dp)
            if (!collapsed) {
                Text("Your Library", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Ws.text)
                WsIconButton(Icons.Rounded.Add, "Create playlist", { c.newPlaylistFor = null; c.newPlaylistOpen = true }, size = 32.dp, iconSize = 20.dp, background = Ws.surfaceHi)
            }
        }
        if (!collapsed) {
            // Filter + sort
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (filtering) {
                    Row(
                        Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(4.dp)).background(Ws.surfaceTop).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.Search, null, Modifier.size(16.dp), tint = Ws.muted)
                        Box(Modifier.weight(1f)) {
                            if (filter.isEmpty()) Text("Search in Your Library", style = MaterialTheme.typography.bodySmall, color = Ws.muted, maxLines = 1)
                            BasicTextField(
                                filter, { filter = it }, singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(color = Ws.text), cursorBrush = SolidColor(Ws.text),
                                modifier = Modifier.fillMaxWidth().onFocusChanged { c.typing = it.isFocused },
                            )
                        }
                    }
                } else {
                    WsIconButton(Icons.Rounded.Search, "Search in Your Library", { filtering = true }, size = 32.dp, iconSize = 18.dp)
                    Box(Modifier.weight(1f))
                }
                WsIconButton(
                    if (alphabetical) Icons.Rounded.SortByAlpha else Icons.Rounded.Schedule,
                    if (alphabetical) "Sorted A to Z (click for recents)" else "Sorted by recents (click for A to Z)",
                    { alphabetical = !alphabetical }, size = 32.dp, iconSize = 18.dp,
                )
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
            items(entries, key = { it.key }) { e -> LibraryRow(c, e, collapsed) }
            if (!collapsed && playlists.isEmpty() && filter.isBlank()) item("empty") { EmptyLibraryCard(c, onPickAndroidFile) }
        }
    }
}

@Composable
private fun LibraryRow(c: Controller, e: LibraryEntry, collapsed: Boolean) {
    val accent = LocalAccent.current
    val selected = c.route == e.route
    HoverRow({ c.navigate(e.route) }, selected = selected) { _ ->
        Row(
            // Collapsed rows show only art, so screen readers get the name from here.
            Modifier.padding(8.dp).then(if (collapsed) Modifier.semantics { contentDescription = e.title } else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (e.icon != null) {
                val bg = if (e.iconTint != null) Modifier.background(e.iconTint) else Modifier.background(Brush.linearGradient(listOf(Ws.defaultAccent, Color(0xFFB8A3F5))))
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).then(bg), contentAlignment = Alignment.Center) {
                    Icon(e.icon, null, Modifier.size(22.dp), tint = if (e.iconTint != null) Ws.muted else Color.White)
                }
            } else Thumb(e.thumb, 48.dp, corner = 4.dp, seed = e.title)
            if (!collapsed) Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.bodyLarge, color = if (selected) accent.highlight else Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.subtitle, style = MaterialTheme.typography.bodySmall, color = Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyLibraryCard(c: Controller, onPickAndroidFile: () -> Unit) {
    Column(
        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Ws.surfaceHi).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Create your first playlist", style = MaterialTheme.typography.titleMedium, color = Ws.text)
        Text("Collect songs you love, or bring your playlists over from the Android app or Spotify.", style = MaterialTheme.typography.bodySmall, color = Ws.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Create playlist", { c.newPlaylistFor = null; c.newPlaylistOpen = true })
        }
        OutlineButton("Import from Android", onPickAndroidFile)
        OutlineButton("Import from Spotify", { c.spotifyImportOpen = true })
    }
}
