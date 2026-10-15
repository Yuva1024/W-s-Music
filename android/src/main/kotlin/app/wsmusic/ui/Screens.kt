package app.wsmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.innertube.models.SongItem
import echopc.Controller
import echopc.DetailUi
import echopc.Route
import echopc.SearchTab
import echopc.db.toSongItem
import java.time.LocalTime

// ============================================================================ Home

@Composable
fun HomeScreen(c: Controller) {
    val history by c.library.history(60).collectAsState(emptyList())
    val recents = remember(history) { history.map { it.song.toSongItem() }.distinctBy { it.id }.take(12) }
    val newUser = c.mixesLoaded && c.mixes.isEmpty() && c.tastePicks.isEmpty() && !c.tastePromptDismissed
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            val h = LocalTime.now().hour
            Text(
                when (h) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Late night listening" },
                Modifier.padding(start = 16.dp, top = 16.dp), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = Ws.text,
            )
        }
        if (c.homeChips.isNotEmpty()) item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(c.homeChips) { _, chip ->
                    FilterChip(c.homeChip?.title == chip.title, { c.selectChip(chip) }, { Text(chip.title) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = Ws.surfaceHi, labelColor = Ws.text, selectedContainerColor = c.accent.primary, selectedLabelColor = c.accent.onPrimary))
                }
            }
        }
        if (newUser) item {
            Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ws.surfaceHi).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tell us what you like", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Ws.text)
                Text("Pick a few artists and your mixes start right away.", color = Ws.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ c.openPicker() }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Pick artists") }
                    OutlinedButton({ c.dismissTastePrompt() }) { Text("Not now", color = Ws.text) }
                }
            }
        }
        if (c.mixes.isNotEmpty()) item {
            Shelf("Made for you") {
                itemsIndexed(c.mixes) { _, mix ->
                    val songs = remember(mix.id, mix.songs) { c.mixSongs(mix) }
                    Card(mix.title, mix.subtitle, songs.firstOrNull()?.thumbnail, { c.navigate(Route.Mix(mix.id)) }, label = mix.title)
                }
            }
        }
        if (recents.isNotEmpty()) item {
            Shelf("Recently played") { itemsIndexed(recents) { i, s -> Card(s.title, s.artists.joinToString { it.name }, s.thumbnail, { c.playFromList(recents, i) }) } }
        }
        c.popularNow?.let { p ->
            if (c.mixes.isEmpty() && p.songs.isNotEmpty()) item {
                Shelf("Popular right now") { itemsIndexed(p.songs) { i, s -> Card(s.title, s.artists.joinToString { it.name }, s.thumbnail, { c.playFromList(p.songs, i) }) } }
            }
        }
        if (c.newReleases.isNotEmpty()) item { ItemShelf(c, "New releases", c.newReleases) }
        c.homeSections.forEachIndexed { i, section ->
            if (section.items.isNotEmpty()) item(key = "sec$i-${section.title}") { ItemShelf(c, section.title, section.items) }
        }
        when {
            c.homeLoading && c.homeSections.isEmpty() -> item { Message("Loading your home…") }
            c.homeError != null && c.homeSections.isEmpty() -> item {
                Column(Modifier.padding(16.dp)) {
                    Text(c.homeError.orEmpty(), color = Ws.muted)
                    OutlinedButton({ c.loadHome() }) { Text("Try again", color = Ws.text) }
                }
            }
            c.homeMore != null -> item {
                LaunchedEffect(c.homeSections.size) { c.loadMoreHome() } // reached the end: fetch more shelves
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ============================================================================ Search

@Composable
fun SearchScreen(c: Controller) {
    val recent by c.library.searchHistory().collectAsState(emptyList())
    Column(Modifier.fillMaxSize()) {
        Text("Search", Modifier.padding(start = 16.dp, top = 16.dp), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = Ws.text)
        TextField(
            c.query, { c.typeQuery(it) },
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(8.dp)),
            placeholder = { Text("What do you want to play?", color = Color(0xFF535353)) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Color.Black) },
            trailingIcon = { if (c.query.isNotEmpty()) IconButton({ c.typeQuery("") }) { Icon(Icons.Filled.Close, "Clear", tint = Color.Black) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { c.search() }),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedTextColor = Color.Black, unfocusedTextColor = Color.Black, cursorColor = Color.Black, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        )
        val typed = c.query.trim()
        when {
            typed.isNotEmpty() && typed != c.lastSearched -> Suggestions(c, typed)
            c.searching && c.searchSummaries.isEmpty() && c.results.isEmpty() -> Message("Searching for “$typed”…")
            c.searchError != null -> Message("Search didn't work: ${c.searchError}. Check your connection and try again.")
            c.searchDone && c.results.isEmpty() && c.searchSummaries.isEmpty() -> Message("No results for “$typed”.")
            c.searchSummaries.isNotEmpty() || c.results.isNotEmpty() -> Results(c)
            else -> LazyColumn {
                if (recent.isNotEmpty()) {
                    item { SectionTitle("Recent searches") }
                    itemsIndexed(recent) { _, h -> Text(h.query, Modifier.fillMaxWidth().clickable { c.search(h.query) }.padding(horizontal = 16.dp, vertical = 12.dp), color = Ws.text) }
                }
                if (c.moods.isNotEmpty()) item {
                    SectionTitle("Browse all")
                    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        c.moods.take(30).chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { m ->
                                    Box(
                                        Modifier.weight(1f).height(80.dp).clip(RoundedCornerShape(8.dp)).background(Color(m.stripeColor.toInt() or (0xFF shl 24)).copy(alpha = 0.85f)).clickable { c.openMood(m) }.padding(12.dp),
                                    ) { Text(m.title, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 2) }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Suggestions(c: Controller, typed: String) {
    val s = c.suggestions?.takeIf { c.suggestionsFor.isNotEmpty() && typed.startsWith(c.suggestionsFor, ignoreCase = true) }
    LazyColumn {
        item { Text("Search for “$typed”", Modifier.fillMaxWidth().clickable { c.search(typed) }.padding(16.dp), color = Ws.text, style = MaterialTheme.typography.bodyLarge) }
        s?.recommendedItems?.take(6)?.let { items ->
            itemsIndexed(items) { _, item ->
                if (item is SongItem) SongRow(c, item, { c.playFromList(listOf(item), 0) })
                else Row(Modifier.fillMaxWidth().clickable { c.openItem(item, emptyList()) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Art(item.thumbnail, 48.dp, round = item is com.music.innertube.models.ArtistItem)
                    Column {
                        Text(item.title, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when (item) { is com.music.innertube.models.ArtistItem -> "Artist"; is com.music.innertube.models.AlbumItem -> "Album"; is com.music.innertube.models.PlaylistItem -> "Playlist"; else -> "" }, color = Ws.muted)
                    }
                }
            }
        }
        s?.queries?.filter { !it.equals(typed, true) }?.take(6)?.let { qs ->
            itemsIndexed(qs) { _, q ->
                Row(Modifier.fillMaxWidth().clickable { c.search(q) }.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Search, null, tint = Ws.muted); Text(q, color = Ws.text)
                }
            }
        }
        if (s == null && c.suggestionsLoading) item { Message("Finding matches…") }
    }
}

@Composable
private fun Results(c: Controller) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(SearchTab.entries) { _, tab ->
                    FilterChip(c.searchTab == tab, { c.selectSearchTab(tab) }, { Text(tab.label) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = Ws.surfaceHi, labelColor = Ws.text, selectedContainerColor = c.accent.primary, selectedLabelColor = c.accent.onPrimary))
                }
            }
        }
        if (c.searchTab == SearchTab.All) {
            if (c.searchSummaries.isEmpty()) songList(c, c.results)
            else c.searchSummaries.forEachIndexed { i, section ->
                item(key = "h$i") { SectionTitle(section.title) }
                val songs = section.items.filterIsInstance<SongItem>()
                if (songs.size == section.items.size) itemsIndexed(songs, key = { j, s -> "s$i-$j-${s.id}" }) { j, s -> SongRow(c, s, { c.playFromList(songs, j) }) }
                else item(key = "r$i") { LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) { itemsIndexed(section.items) { _, it -> ItemCard(c, it, section.items) } } }
            }
        } else {
            val songs = c.tabItems.filterIsInstance<SongItem>()
            if (songs.isNotEmpty() && songs.size == c.tabItems.size) songList(c, songs)
            else itemsIndexed(c.tabItems.chunked(2)) { _, row -> Row(Modifier.padding(horizontal = 8.dp)) { row.forEach { ItemCard(c, it, c.tabItems) } } }
            item {
                when {
                    c.tabLoading -> Message("Loading…")
                    c.tabItems.isEmpty() -> Message("No ${c.searchTab.label.lowercase()} found.")
                    c.tabMore -> OutlinedButton({ c.loadMoreSearch() }, Modifier.padding(16.dp)) { Text("Show more", color = Ws.text) }
                }
            }
        }
    }
}

private fun LazyListScope.songList(c: Controller, songs: List<SongItem>) {
    itemsIndexed(songs, key = { i, s -> "song$i-${s.id}" }) { i, s -> SongRow(c, s, { c.playFromList(songs, i) }) }
}

// ============================================================================ Library

@Composable
fun LibraryScreen(c: Controller) {
    val liked by c.library.likedSongs.collectAsState(emptyList())
    val playlists by c.library.playlists.collectAsState(emptyList())
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Your Library", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = Ws.text)
                IconButton({ c.newPlaylistFor = null; c.newPlaylistOpen = true }) { Icon(Icons.Filled.Add, "Create playlist", tint = Ws.text) }
            }
        }
        item { LibraryRow("Liked Songs", "Playlist • ${liked.size} songs", null, Icons.Filled.Favorite) { c.navigate(Route.Liked) } }
        item { LibraryRow("Recently played", "Your listening history", null, WsIcons.History) { c.navigate(Route.History) } }
        item { LibraryRow("Your songs", "Liked and imported songs", null, WsIcons.Library) { c.navigate(Route.LibrarySongs) } }
        if (c.mixes.isNotEmpty()) itemsIndexed(c.mixes) { _, mix ->
            val songs = remember(mix.id, mix.songs) { c.mixSongs(mix) }
            LibraryRow(mix.title, "Made for you • ${songs.size} songs", songs.firstOrNull()?.thumbnail, null) { c.navigate(Route.Mix(mix.id)) }
        }
        itemsIndexed(playlists) { _, p ->
            LibraryRow(p.playlist.name, "Playlist • ${p.songCount} songs", p.thumbnails.firstOrNull(), WsIcons.Queue) { c.navigate(Route.LocalPlaylist(p.playlist.id)) }
        }
        item {
            Text("Pick artists you like", Modifier.fillMaxWidth().clickable { c.openPicker() }.padding(16.dp), color = c.accent.highlight)
        }
        item {
            // App version and a manual update check (the app also checks by itself shortly after it starts).
            val version = echopc.AppHooks.updates?.currentVersion().orEmpty()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("W's MUSIC $version", Modifier.weight(1f), color = Ws.muted, style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ if (c.update != null) c.updateDialogOpen = true else c.checkForUpdates(manual = true) }) {
                    Text(if (c.update != null) "Update to ${c.update?.version}" else "Check for updates", color = Ws.text)
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String, thumb: String?, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (thumb != null || icon == null) Art(thumb, 56.dp)
        else Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(Ws.surfaceHi), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Ws.text) }
        Column {
            Text(title, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = Ws.muted, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ============================================================================ Collection pages

@Composable
fun CollectionPage(c: Controller, kind: String, title: String, subtitle: String, cover: String?, songs: List<SongItem>, empty: String = "Nothing here yet.") {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.padding(8.dp)) { IconButton({ c.goBack() }) { Icon(Icons.Filled.ArrowBack, "Back", tint = Ws.text) } }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Art(cover ?: songs.firstOrNull()?.thumbnail, 200.dp, corner = 8.dp)
                Text(title, color = Ws.text, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(kind, subtitle).filter { it.isNotBlank() }.joinToString(" • "), color = Ws.muted, maxLines = 2)
            }
            if (songs.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ c.playShuffled(songs) }) { Icon(WsIcons.Shuffle, null, tint = Ws.text); Text("  Shuffle", color = Ws.text) }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(50)).background(c.accent.primary).clickable { c.playCollection(songs) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, "Play", tint = c.accent.onPrimary, modifier = Modifier.size(32.dp))
                }
            }
        }
        if (songs.isEmpty()) item { Message(empty) }
        itemsIndexed(songs, key = { i, s -> "c$i-${s.id}" }) { i, s -> SongRow(c, s, { c.playFromList(songs, i) }) }
    }
}

@Composable
fun LikedScreen(c: Controller) {
    val liked by c.library.likedSongs.collectAsState(emptyList())
    val songs = remember(liked) { liked.map { it.toSongItem() } }
    CollectionPage(c, "Playlist", "Liked Songs", "${songs.size} songs", null, songs, "Songs you like show up here. Tap the heart on any song.")
}

@Composable
fun LibrarySongsScreen(c: Controller) {
    val list by c.library.librarySongs.collectAsState(emptyList())
    val songs = remember(list) { list.map { it.toSongItem() } }
    CollectionPage(c, "Collection", "Your songs", "${songs.size} songs", null, songs)
}

@Composable
fun HistoryScreen(c: Controller) {
    val events by c.library.history().collectAsState(emptyList())
    val songs = remember(events) { events.map { it.song.toSongItem() } }
    CollectionPage(c, "History", "Recently played", "${songs.size} plays", null, songs, "Songs you listen to for at least 10 seconds show up here.")
}

@Composable
fun LocalPlaylistScreen(c: Controller, id: String) {
    val playlist by c.library.playlist(id).collectAsState(null)
    val rows by c.library.playlistSongs(id).collectAsState(emptyList())
    val songs = remember(rows) { rows.map { it.song.toSongItem() } }
    CollectionPage(c, "Playlist", playlist?.playlist?.name.orEmpty(), "${songs.size} songs", playlist?.thumbnails?.firstOrNull(), songs, "This playlist is empty. Use ⋮ on any song and choose Add to playlist.")
}

@Composable
fun MixScreen(c: Controller, id: String) {
    val mix = c.mixes.firstOrNull { it.id == id }
    if (mix == null) { Message("This mix is being rebuilt. Try again in a moment."); return }
    val songs = remember(mix.id, mix.songs) { c.mixSongs(mix) }
    CollectionPage(c, "Made for you", mix.title, mix.subtitle, songs.firstOrNull()?.thumbnail, songs)
}

@Composable
fun OnlineScreen(c: Controller, key: String) {
    when (val d = c.details[key]) {
        null, is DetailUi.Loading -> Column { BackRow(c); Message("Loading ${d?.title.orEmpty()}…") }
        is DetailUi.Failed -> Column { BackRow(c); Message("${d.title}: ${d.message}") }
        is DetailUi.Songs -> CollectionPage(c, d.kind, d.title, d.subtitle, d.cover, d.songs)
        is DetailUi.Collection -> LazyColumn(Modifier.fillMaxSize()) {
            item { BackRow(c); Text(d.title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = Ws.text) }
            d.sections.forEachIndexed { i, s -> item(key = "o$i") { ItemShelf(c, s.title ?: "Featured", s.items) } }
        }
    }
}

@Composable
private fun BackRow(c: Controller) {
    Row(Modifier.padding(8.dp)) { IconButton({ c.goBack() }) { Icon(Icons.Filled.ArrowBack, "Back", tint = Ws.text) } }
}

// ============================================================================ Artist picker

@Composable
fun PickerScreen(c: Controller) {
    val tiles = if (c.pickerQuery.isNotBlank()) c.pickerResults else (c.pickerSelected.toList() + c.pickerSimilar + c.suggestedArtists).distinctBy { it.id }
    Column(Modifier.fillMaxSize()) {
        BackRow(c)
        Text("Choose artists you like", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = Ws.text)
        TextField(
            c.pickerQuery, { c.searchPickerArtists(it) }, Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Search artists") }, singleLine = true,
        )
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
            items(tiles, key = { it.id }) { a ->
                val picked = c.pickerSelected.any { it.id == a.id }
                Column(Modifier.clickable { c.togglePick(a) }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        Art(a.thumbnail, 96.dp, round = true)
                        if (picked) Box(Modifier.size(96.dp).clip(RoundedCornerShape(50)).background(c.accent.primary.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) { Text("✓", color = Color.White, style = MaterialTheme.typography.headlineMedium) }
                    }
                    Text(a.title, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${c.pickerSelected.size} picked", Modifier.weight(1f), color = Ws.muted)
            OutlinedButton({ c.skipPicker() }) { Text("Skip", color = Ws.text) }
            Button({ c.savePicks() }, enabled = c.pickerSelected.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Done") }
        }
    }
}
