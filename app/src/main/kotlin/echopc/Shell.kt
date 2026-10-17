package echopc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/** Gap between the panels; the black window shows through. */
private val Gap = 8.dp

/** The mouse's side buttons (4 = back, 5 = forward) move through pages like a browser's, anywhere in the window. */
private fun Modifier.sideButtonNavigation(c: Controller): Modifier = pointerInput(c) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Press) continue
            val back = event.buttons.isBackPressed
            val forward = event.buttons.isForwardPressed
            if (back) c.goBack() else if (forward) c.goForward()
            if (back || forward) event.changes.forEach { it.consume() }
        }
    }
}

@Composable
fun AppContent(c: Controller, onPickAndroidFile: () -> Unit, onPickSpotifyFile: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().background(Ws.bg0).sideButtonNavigation(c)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(c, onPickAndroidFile)
            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Gap)) {
                LibrarySidebar(c, onPickAndroidFile)
                SidebarResizer(c)
                MainView(c, Modifier.weight(1f).fillMaxHeight())
                if (c.panelOpen) {
                    Box(Modifier.width(Gap))
                    RightPanel(c)
                }
            }
            PlayerBar(c)
        }
        // Toast: just above the player bar, like a snackbar, optionally with an Undo button.
        c.toast?.let { message ->
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Ws.text).padding(start = 16.dp, end = if (c.toastAction != null) 8.dp else 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(message, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Color.Black)
                c.toastAction?.let { action ->
                    Text(
                        action.label,
                        Modifier.clip(RoundedCornerShape(50)).clickable { c.runToastAction() }.handCursor().padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge, color = Color.Black,
                    )
                }
            }
        }
        if (c.shortcutsOpen) ShortcutsDialog { c.shortcutsOpen = false }
        if (c.updateDialogOpen) UpdateDialog(c)
        if (c.newPlaylistOpen) NameDialog("New playlist", "", "Create", onConfirm = c::createPlaylist, onDismiss = { c.newPlaylistOpen = false; c.newPlaylistFor = null })
        ImportDialog(c)
        if (c.spotifyImportOpen) SpotifyImportDialog(c, onPickSpotifyFile)
        // A phone asking to pair opens the sync dialog so the code can be checked.
        androidx.compose.runtime.LaunchedEffect(c.sync.pairRequest) { if (c.sync.pairRequest != null) c.syncOpen = true }
        if (c.syncOpen) SyncDialog(c)
    }
}

/** The page area: one rounded panel whose content follows [Controller.route]. */
@Composable
private fun MainView(c: Controller, modifier: Modifier) {
    Box(modifier.panel()) {
        when (val r = c.route) {
            Route.Home -> HomeScreen(c)
            Route.Search -> SearchPage(c)
            Route.Liked -> LikedPage(c)
            Route.LibrarySongs -> LibrarySongsPage(c)
            Route.History -> HistoryPage(c)
            Route.Lyrics -> LyricsPage(c)
            is Route.LocalPlaylist -> LocalPlaylistPage(c, r.id)
            is Route.Online -> OnlinePage(c, c.details[r.key])
            is Route.Mix -> MixPage(c, r.id)
            Route.Picker -> TastePickerPage(c)
        }
    }
}

/** Drag handle between the library and the page; drag to resize, the sidebar collapses when narrow. */
@Composable
private fun SidebarResizer(c: Controller) {
    val density = LocalDensity.current.density
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var dragTotal by remember { mutableStateOf(0f) }
    Box(
        Modifier.width(Gap).fillMaxHeight()
            .hoverable(source)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(
                rememberDraggableState { px ->
                    val dp = px / density
                    if (c.sidebarCollapsed) {
                        dragTotal += dp
                        if (dragTotal > 60f) { c.sidebarCollapsed = false; c.sidebarWidth = SIDEBAR_MIN; dragTotal = 0f }
                    } else {
                        val w = c.sidebarWidth + dp
                        if (w < SIDEBAR_MIN - 80f) { c.sidebarCollapsed = true; dragTotal = 0f } else c.sidebarWidth = w.coerceIn(SIDEBAR_MIN, SIDEBAR_MAX)
                    }
                },
                Orientation.Horizontal,
                onDragStopped = { dragTotal = 0f },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (hovered) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp).background(Ws.faint))
    }
}

// =====================================================================================
//  Top bar
// =====================================================================================

@Composable
private fun TopBar(c: Controller, onPickAndroidFile: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandMark()
            Box(Modifier.width(8.dp))
            WsIconButton(Icons.Rounded.ChevronLeft, "Go back", { c.goBack() }, size = 32.dp, iconSize = 24.dp, enabled = c.canGoBack, background = Ws.surface)
            WsIconButton(Icons.Rounded.ChevronRight, "Go forward", { c.goForward() }, size = 32.dp, iconSize = 24.dp, enabled = c.canGoForward, background = Ws.surface)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val home = c.route == Route.Home
            WsIconButton(if (home) Icons.Rounded.Home else Icons.Outlined.Home, "Home", { c.goHome() }, size = 48.dp, iconSize = 24.dp, tint = if (home) Ws.text else Ws.muted, background = Ws.surfaceHi)
            TopSearch(c)
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            val pending = c.update
            WsIconButton(
                Icons.Rounded.SystemUpdateAlt, if (pending != null) "Update available: version ${pending.version}" else "Check for updates",
                { if (pending != null) c.updateDialogOpen = true else c.checkForUpdates(manual = true) },
                size = 32.dp, iconSize = 20.dp, tint = if (pending != null) LocalAccent.current.highlight else Ws.muted,
            )
            WsIconButton(Icons.Rounded.Keyboard, "Keyboard shortcuts", { c.shortcutsOpen = true }, size = 32.dp, iconSize = 20.dp, shortcut = Shortcut.HELP.keys)
            WsIconButton(Icons.Rounded.FileUpload, "Import from the Android app", onPickAndroidFile, size = 32.dp, iconSize = 18.dp)
            WsIconButton(Icons.Rounded.LibraryMusic, "Import from Spotify", { c.spotifyImportOpen = true }, size = 32.dp, iconSize = 18.dp)
            WsIconButton(Icons.Rounded.Sync, "Sync with your phone", { c.syncOpen = true }, size = 32.dp, iconSize = 18.dp)
        }
    }
}

/** The W's MUSIC logo (a W on a vinyl record), white on black (from branding/). */
@Composable
private fun BrandMark() {
    val logo = remember {
        Thread.currentThread().contextClassLoader?.getResourceAsStream("logo-mark.png")?.use { javax.imageio.ImageIO.read(it) }?.toComposeImageBitmap()
    }
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        if (logo != null) androidx.compose.foundation.Image(logo, "W's MUSIC", Modifier.size(26.dp))
        else Text("W", style = MaterialTheme.typography.titleMedium.copy(fontFamily = BrandFont), color = Color.White)
    }
}

@Composable
private fun TopSearch(c: Controller) {
    var focused by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(50)
    val focusRequester = remember { FocusRequester() }
    // Ctrl+L: jump to the search box.
    LaunchedEffect(c.searchFocusTick) { if (c.searchFocusTick > 0) runCatching { focusRequester.requestFocus() } }
    Row(
        Modifier.widthIn(min = 280.dp, max = 480.dp).width(420.dp).height(48.dp).clip(shape).background(if (hovered || focused) Ws.surfaceTop else Ws.surfaceHi)
            .border(if (focused) 2.dp else 0.dp, if (focused) Ws.text else Color.Transparent, shape)
            .hoverable(source).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(22.dp), tint = if (focused) Ws.text else Ws.muted)
        Box(Modifier.weight(1f)) {
            if (c.query.isEmpty()) Text("What do you want to play?", style = MaterialTheme.typography.bodyLarge, color = Ws.muted, maxLines = 1)
            BasicTextField(
                c.query, { c.typeQuery(it) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ws.text),
                cursorBrush = SolidColor(Ws.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { c.search() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused; c.typing = it.isFocused; if (it.isFocused) c.navigate(Route.Search) }
                    .onKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { c.search(); true } else false },
            )
        }
        if (c.query.isNotEmpty()) WsIconButton(Icons.Rounded.Close, "Clear search", { c.typeQuery("") }, size = 32.dp, iconSize = 18.dp)
    }
}

// =====================================================================================
//  Dialogs
// =====================================================================================

/** "A newer version is available": what it is, where it comes from, and one button to install it. */
@Composable
fun UpdateDialog(c: Controller) {
    val info = c.update ?: return
    val busy = c.updateProgress != null
    AlertDialog(
        onDismissRequest = { if (!busy) c.updateDialogOpen = false },
        containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
        title = { Text("Update available", style = MaterialTheme.typography.headlineMedium, color = Ws.text) },
        text = { UpdateDialogBody(c, info) },
        confirmButton = { PrimaryButton(if (c.updateError != null) "Try again" else "Update now", { c.installUpdate() }, enabled = !busy) },
        dismissButton = { OutlineButton("Not now", { c.updateDialogOpen = false }, enabled = !busy) },
    )
}

/** The words and progress bar of the update dialog (separate so it can be tested without opening a dialog window). */
@Composable
internal fun UpdateDialogBody(c: Controller, info: UpdateInfo) {
    Column(Modifier.widthIn(min = 380.dp, max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Version ${info.version} is ready (you have ${Updater.currentVersion()}).", style = MaterialTheme.typography.bodyLarge, color = Ws.text)
        if (info.notes.isNotBlank()) Text(info.notes.take(400), style = MaterialTheme.typography.bodyMedium, color = Ws.muted, maxLines = 8, overflow = TextOverflow.Ellipsis)
        Text("W's MUSIC closes, the installer opens, and your library, likes and settings are kept. From ${info.repo} on GitHub.", style = MaterialTheme.typography.bodySmall, color = Ws.muted)
        c.updateProgress?.let { p ->
            androidx.compose.material3.LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth(), color = LocalAccent.current.highlight, trackColor = Ws.surfaceTop)
            Text("Downloading… ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = Ws.muted)
        }
        c.updateError?.let { Text("Update failed: $it", style = MaterialTheme.typography.bodyMedium, color = Ws.danger) }
    }
}

/** All keyboard shortcuts, grouped, from the same table the key handling uses. */
@Composable
fun ShortcutsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
        title = { Text("Keyboard shortcuts", style = MaterialTheme.typography.headlineMedium, color = Ws.text) },
        text = {
            Column(Modifier.widthIn(min = 420.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Shortcut.entries.groupBy { it.group }.forEach { (group, items) ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(group, style = MaterialTheme.typography.titleSmall, color = Ws.text)
                        items.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
                                Text(
                                    s.keys, Modifier.clip(RoundedCornerShape(4.dp)).background(Ws.surfaceTop).padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelMedium, color = Ws.text,
                                )
                            }
                        }
                    }
                }
                Text("Right-click any song to see its options. Shortcuts stand down while you type in a text box.", style = MaterialTheme.typography.bodySmall, color = Ws.muted)
            }
        },
        confirmButton = { PrimaryButton("Close", onDismiss) },
    )
}

@Composable
fun NameDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
        title = { Text(title, style = MaterialTheme.typography.headlineMedium, color = Ws.text) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }, shape = RoundedCornerShape(4.dp)) },
        confirmButton = { PrimaryButton(confirmLabel, { onConfirm(text) }, enabled = text.isNotBlank()) },
        dismissButton = { OutlineButton("Cancel", onDismiss) },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
        title = { Text(title, style = MaterialTheme.typography.headlineMedium, color = Ws.text) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, color = Ws.muted) },
        confirmButton = { OutlineButton(confirmLabel, onConfirm, danger = true) },
        dismissButton = { OutlineButton("Cancel", onDismiss) },
    )
}

/** Paste a Spotify playlist or album link, or choose the Spotify data download for everything (all playlists, likes, history). */
@Composable
private fun SpotifyImportDialog(c: Controller, onPickFile: () -> Unit) {
    var link by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { c.spotifyImportOpen = false }, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
        title = { Text("Import from Spotify", style = MaterialTheme.typography.headlineMedium, color = Ws.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paste a playlist or album link (in Spotify: Share › Copy link). The songs become a playlist here and shape your taste and mixes.",
                    style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
                OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Spotify link") }, shape = RoundedCornerShape(4.dp))
                Text("Long playlists (over 100 songs), private playlists, liked songs and listening history: in Spotify go to Account › Privacy › Download your data, then choose the .zip they email you.",
                    style = MaterialTheme.typography.bodySmall, color = Ws.muted)
                OutlineButton("Choose Spotify data file…", { c.spotifyImportOpen = false; onPickFile() })
            }
        },
        confirmButton = { PrimaryButton("Import", { c.importSpotifyLink(link) }, enabled = SpotifyLink.looksLikeLink(link)) },
        dismissButton = { OutlineButton("Cancel", { c.spotifyImportOpen = false }) },
    )
}

@Composable
private fun ImportDialog(c: Controller) {
    when (val s = c.importUi) {
        ImportUi.Idle -> Unit
        is ImportUi.Running -> AlertDialog(
            onDismissRequest = {}, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
            title = { Text("Importing", style = MaterialTheme.typography.headlineMedium, color = Ws.text) },
            text = { Text(s.detail.ifEmpty { "Reading ${s.fileName}. This takes a few seconds." }, style = MaterialTheme.typography.bodyMedium, color = Ws.muted) },
            confirmButton = { if (s.cancellable) OutlineButton("Stop", { c.cancelSpotifyImport() }) },
        )
        is ImportUi.Message -> AlertDialog(
            onDismissRequest = { c.importUi = ImportUi.Idle }, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
            title = { Text(s.title, style = MaterialTheme.typography.headlineMedium, color = Ws.text) },
            text = { Text(s.text, style = MaterialTheme.typography.bodyMedium, color = Ws.muted) },
            confirmButton = { PrimaryButton("Done", { c.importUi = ImportUi.Idle }) },
        )
        is ImportUi.Done -> AlertDialog(
            onDismissRequest = { c.importUi = ImportUi.Idle }, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
            title = { Text("Import complete", style = MaterialTheme.typography.headlineMedium, color = Ws.text) },
            text = { Text(s.result.summary(), style = MaterialTheme.typography.bodyMedium, color = Ws.muted) },
            confirmButton = { PrimaryButton("Done", { c.importUi = ImportUi.Idle }) },
        )
        is ImportUi.Failed -> AlertDialog(
            onDismissRequest = { c.importUi = ImportUi.Idle }, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
            title = { Text("Import failed", style = MaterialTheme.typography.headlineMedium, color = Ws.danger) },
            text = { Text(s.message, style = MaterialTheme.typography.bodyMedium, color = Ws.muted) },
            confirmButton = { OutlineButton("Close", { c.importUi = ImportUi.Idle }) },
        )
    }
}
