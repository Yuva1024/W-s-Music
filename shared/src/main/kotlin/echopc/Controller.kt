package echopc

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.innertube.pages.BrowseResult
import com.music.innertube.pages.HomePage
import com.music.innertube.pages.MoodAndGenres
import com.music.innertube.models.SearchSuggestions
import com.music.innertube.pages.SearchSummary
import echopc.db.ImportResult
import echopc.db.EndReason
import echopc.db.Feedback
import echopc.db.artistKey
import echopc.db.GeneratedMix
import echopc.db.LibraryRepository
import echopc.db.StartReason
import echopc.db.TastePick
import echopc.db.TrackCodec
import echopc.reco.CandidateSource
import echopc.reco.MixKind
import echopc.reco.Recommender
import echopc.reco.ShelfBandit
import echopc.reco.YouTubeCandidates
import java.io.File
import java.util.Properties
import echopc.sync.RemoteCommand
import echopc.sync.RemotePlayer
import echopc.sync.RemoteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Search result views: "All" is YouTube Music's mixed page, the others are its filtered lists (paged). */
enum class SearchTab(val label: String) { All("All"), Songs("Songs"), Videos("Videos"), Albums("Albums"), Artists("Artists"), Playlists("Playlists") }

/** A page of the main view. The controller keeps a back stack of these (top bar back/forward). */
/** What happens to the music while someone talks in voice chat (Discord). */
enum class VoiceDuck { OFF, LOWER, PAUSE }

sealed interface Route {
    data object Home : Route
    data object Search : Route
    data object Liked : Route
    data object LibrarySongs : Route
    data object History : Route
    data object Lyrics : Route
    data class LocalPlaylist(val id: String) : Route
    /** A generated mix (Daily Mix, Discover Weekly, ...). */
    data class Mix(val id: String) : Route
    /** "Tell us what you like": pick artists to seed taste. */
    data object Picker : Route
    /** An online playlist / album / mood page; its content lives in [Controller.details] under [key]. */
    data class Online(val key: String) : Route
    /** Songs saved for offline listening. */
    data object Downloads : Route
    /** Music files on this device. */
    data object LocalFiles : Route
}

/** The "where it was played from" string stored with each play. */
fun routeContext(r: Route): String = when (r) {
    Route.Home -> "home"
    Route.Search -> "search"
    Route.Liked -> "liked"
    Route.LibrarySongs -> "library"
    Route.History -> "history"
    Route.Lyrics -> "lyrics"
    is Route.LocalPlaylist -> "local:${r.id}"
    is Route.Online -> r.key
    is Route.Mix -> "mix:${r.id}"
    Route.Picker -> "picker"
    Route.Downloads -> "downloads"
    Route.LocalFiles -> "localfiles"
}

/** What the user asked us to avoid, as quick lookups (from the `feedback` table). */
data class Blocks(
    val notInterested: Set<String> = emptySet(),
    val bannedArtists: Set<String> = emptySet(),
    val excluded: Set<String> = emptySet(),
    val hidden: Map<String, Set<String>> = emptyMap(),
) {
    /** True when [song] must not play in [context] (or at all). */
    fun blocked(song: SongItem, context: String): Boolean =
        song.id in notInterested || song.artistKey() in bannedArtists || song.id in hidden[context].orEmpty()

    companion object {
        fun from(list: List<Feedback>) = Blocks(
            notInterested = list.filter { it.kind == Feedback.Kind.NOT_INTERESTED }.map { it.targetId }.toSet(),
            bannedArtists = list.filter { it.kind == Feedback.Kind.BAN_ARTIST }.map { it.targetId }.toSet(),
            excluded = list.filter { it.kind == Feedback.Kind.EXCLUDE_FROM_TASTE }.map { it.targetId }.toSet(),
            hidden = list.filter { it.kind == Feedback.Kind.HIDE_IN_CONTEXT }.groupBy({ it.context }, { it.targetId }).mapValues { it.value.toSet() },
        )
    }
}

/** A toast's button, e.g. Undo. */
class ToastAction(val label: String, val run: () -> Unit)

/**
 * What went wrong, in words for a person. YouTube's errors arrive as raw HTTP and JSON text, which must never reach the screen.
 * Sentence-case and without a trailing full stop, so callers can add their own advice after it.
 */
fun friendlyError(t: Throwable): String {
    // Class names count too: an UnknownHostException's message is only the host name.
    val text = generateSequence(t) { it.cause }.joinToString(" ") { "${it::class.simpleName} ${it.message.orEmpty()}" }.lowercase()
    return when {
        listOf("unknownhost", "connectexception", "unable to resolve", "no address associated", "network is unreachable", "no route to host", "connection refused", "failed to connect", "connect timed out", "unresolved")
            .any { it in text } -> "There's no internet connection"
        listOf("429", "too many requests", "quota").any { it in text } -> "YouTube Music is busy right now"
        listOf("500", "502", "503", "504", "unavailable", "backenderror", "bad gateway").any { it in text } -> "YouTube Music is temporarily unavailable"
        "timeout" in text || "timed out" in text -> "The connection is too slow right now"
        else -> "Something went wrong loading this"
    }
}

/** The player bar's reason for a song that would not play, in plain words (the technical text goes to wsmusic.log). */
fun playbackErrorText(message: String): String {
    val text = message.lowercase()
    return when {
        "attestation" in text || "stream protection" in text -> "YouTube is limiting streams for a moment. Wait a few seconds and play it again."
        "no playable stream" in text || "found no stream" in text -> "YouTube has no stream for this song right now."
        else -> {
            val friendly = friendlyError(RuntimeException(message))
            if (friendly == "Something went wrong loading this") message.take(120) else friendly
        }
    }
}

/** What the right-hand panel shows. */
enum class PanelTab { NOW_PLAYING, QUEUE }

/** Library sidebar width limits in dp (it can also collapse to an icon strip). */
const val SIDEBAR_MIN = 240f
const val SIDEBAR_MAX = 440f

/** Content of an online playlist / album / mood page. */
sealed interface DetailUi {
    val title: String
    data class Loading(override val title: String) : DetailUi
    data class Failed(override val title: String, val message: String) : DetailUi
    data class Songs(
        override val title: String,
        val kind: String, // "Playlist" / "Album"
        val subtitle: String,
        val cover: String?,
        val songs: List<SongItem>,
    ) : DetailUi
    data class Collection(override val title: String, val sections: List<BrowseResult.Item>) : DetailUi
}

sealed interface ImportUi {
    data object Idle : ImportUi
    data class Running(val fileName: String, val detail: String = "", val cancellable: Boolean = false) : ImportUi
    /** A finished import with a plain-words summary. */
    data class Message(val title: String, val text: String) : ImportUi
    data class Done(val result: ImportResult) : ImportUi
    data class Failed(val message: String) : ImportUi
}

/** Accent pair derived from album art: the dominant colour plus a hue-shifted partner for gradients. */
fun accentFrom(dominant: Color): Accent {
    val hsb = rgbToHsb(dominant.red, dominant.green, dominant.blue)
    // Warm art (red/orange/yellow) pairs with a pink-red; cooler art pairs with the next hue along.
    val warm = hsb[0] < 0.17f || hsb[0] > 0.93f
    val shift = if (warm) -0.07f else 0.10f
    val partnerHue = ((hsb[0] + shift) % 1f + 1f) % 1f
    val partner = hsbToColor(partnerHue, (hsb[1] * 0.9f).coerceIn(0.5f, 0.95f), 1f)
    return Accent(dominant, partner)
}

/** Hue, saturation, brightness (0..1) of an RGB colour (0..1), as java.awt.Color.RGBtoHSB computes them. */
internal fun rgbToHsb(r: Float, g: Float, b: Float): FloatArray {
    val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
    val s = if (max == 0f) 0f else d / max
    val h = when {
        d == 0f -> 0f
        max == r -> ((g - b) / d).let { if (it < 0) it + 6f else it } / 6f
        max == g -> ((b - r) / d + 2f) / 6f
        else -> ((r - g) / d + 4f) / 6f
    }
    return floatArrayOf(h, s, max)
}

internal fun hsbToColor(h: Float, s: Float, v: Float): Color {
    val i = (h * 6f).toInt() % 6
    val f = h * 6f - (h * 6f).toInt()
    val p = v * (1 - s); val q = v * (1 - f * s); val t = v * (1 - (1 - f) * s)
    val (r, g, b) = when (i) { 0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t); 3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q) }
    return Color(r, g, b)
}

/**
 * All app logic and state. Composables only read from it, so screens can be rendered with a fake
 * player in tests. Playback position has its own state so that only the widgets that show it redraw
 * as it advances (not the whole window).
 */
@Stable
class Controller(
    private val scope: CoroutineScope,
    val library: LibraryRepository,
    private val settingsFile: File? = null,
    recoSource: CandidateSource? = null,
    discoverySource: DiscoverySource? = null,
    engineFactory: (onState: (PlayerState) -> Unit, onEnded: () -> Unit) -> PlayerEngine,
) : RemotePlayer {
    // ---- navigation ----
    var backStack by mutableStateOf<List<Route>>(listOf(Route.Home))
        private set
    private var forwardStack by mutableStateOf<List<Route>>(emptyList())
    val route: Route get() = backStack.last()
    val canGoBack: Boolean get() = backStack.size > 1
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()
    val details = mutableStateMapOf<String, DetailUi>()
    var panelOpen by mutableStateOf(true)
    var panelTab by mutableStateOf(PanelTab.NOW_PLAYING)
    var sidebarWidth by mutableFloatStateOf(300f) // dp
    var sidebarCollapsed by mutableStateOf(false)

    // ---- home feed ----
    var homeSections by mutableStateOf<List<HomePage.Section>>(emptyList())
    var homeChips by mutableStateOf<List<HomePage.Chip>>(emptyList())
    var homeChip by mutableStateOf<HomePage.Chip?>(null)
    var homeLoading by mutableStateOf(false)
    var homeError by mutableStateOf<String?>(null)
    var homeMore by mutableStateOf<String?>(null) // continuation token for more shelves
    var homeLoadingMore by mutableStateOf(false)
    var newReleases by mutableStateOf<List<AlbumItem>>(emptyList())
    var moods by mutableStateOf<List<MoodAndGenres.Item>>(emptyList())

    // ---- search ----
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SongItem>>(emptyList())
    var searching by mutableStateOf(false)
    var searchError by mutableStateOf<String?>(null)
    var searchDone by mutableStateOf(false)            // a search finished (so "no results" can be told apart from "not searched yet")
    var searchTab by mutableStateOf(SearchTab.All)
    var searchSummaries by mutableStateOf<List<SearchSummary>>(emptyList()) // the "All" view: top result, songs, videos, albums, artists, playlists
    var tabItems by mutableStateOf<List<YTItem>>(emptyList())
    var tabLoading by mutableStateOf(false)
    var tabMore by mutableStateOf(false)
    private var tabContinuation: String? = null
    private val tabCache = HashMap<SearchTab, Pair<List<YTItem>, String?>>()
    // ---- self-update ----
    var update by mutableStateOf<UpdateInfo?>(null)
    var updateDialogOpen by mutableStateOf(false)
    var updateProgress by mutableStateOf<Float?>(null)   // 0..1 while the installer downloads
    var updateError by mutableStateOf<String?>(null)
    /** "Installing…" once the download is done (silent updates), shown under the progress bar. */
    var updateStage by mutableStateOf<String?>(null)
    /** Set by the window: closes the app (saving everything) so the installer can replace it. */
    var requestExit: (() -> Unit)? = null

    private var searchedQuery = ""
    /** What the last finished search was for; the typing view shows while the box differs from it. */
    var lastSearched by mutableStateOf("")
        set // public so tests can stand in for a finished search
    var suggestions by mutableStateOf<SearchSuggestions?>(null)
    var suggestionsFor by mutableStateOf("")
    var suggestionsLoading by mutableStateOf(false)
    private var suggestJob: Job? = null
    private var searchGeneration = 0

    // ---- playback ----
    var queue by mutableStateOf(QueueState())
    var playback by mutableStateOf(PlayerState()) // positionMs intentionally unused here
    var positionMs by mutableLongStateOf(0L)
    var volume by mutableFloatStateOf(0.8f)
    var autoplay by mutableStateOf(true)

    // ---- extras ----
    var lyrics by mutableStateOf<LyricsUiState>(LyricsUiState.Idle)
    var accent by mutableStateOf(Accent.DEFAULT)
    var toast by mutableStateOf<String?>(null)
    var toastAction by mutableStateOf<ToastAction?>(null)
    var importUi by mutableStateOf<ImportUi>(ImportUi.Idle)
    /** The "Import from Spotify" dialog (paste a link, or choose the Spotify data file). */
    var spotifyImportOpen by mutableStateOf(false)
    /** Text for the dialog's link field (filled when a link is shared to the phone app from Spotify). */
    var spotifyImportDraft by mutableStateOf("")
    var likedIds by mutableStateOf<Set<String>>(emptySet())
    var newPlaylistFor by mutableStateOf<SongItem?>(null) // song to add once created
    var newPlaylistOpen by mutableStateOf(false)
    var typing by mutableStateOf(false) // a text field has focus: keyboard shortcuts stand down

    // ---- recommendations ----
    val recommender = Recommender(library, recoSource ?: YouTubeCandidates(library))

    /** Wi-Fi sync with the user's other device (phone <-> computer). Started by [startSync]. */
    val sync = echopc.sync.SyncManager(library, scope, onChanged = { changed ->
        if (echopc.sync.SyncCategory.TASTE in changed || echopc.sync.SyncCategory.LIBRARY in changed) {
            recommender.invalidateEmpty()
            refreshMixes()
        }
    })
    var syncOpen by mutableStateOf(false)

    /** Songs saved for offline listening. */
    val downloads = Downloads(library, scope) { showToast(it) }

    /** Music files on this device (null until looked for). */
    var localSongs by mutableStateOf<List<SongItem>?>(null)
    var localScanning by mutableStateOf(false)
    var localError by mutableStateOf<String?>(null)
    /** Set when the phone must ask for permission to read music; the screen asks, then calls [scanLocalMusic]. */
    var localNeedsPermission by mutableStateOf(false)

    /** Looks for music files on this device again. */
    fun scanLocalMusic() {
        val source = AppHooks.localMusic ?: run { localError = "Not available here"; return }
        if (localScanning) return
        localScanning = true; localError = null
        scope.launch(Dispatchers.IO) {
            runCatching { source.scan() }
                .onSuccess { localSongs = it; localNeedsPermission = false }
                .onFailure { e ->
                    if (e is SecurityException) localNeedsPermission = true
                    else localError = e.message ?: "Couldn't read the music on this device"
                }
            localScanning = false
        }
    }

    fun startSync() = sync.start()
    var mixes by mutableStateOf<List<GeneratedMix>>(emptyList())
    var mixesRefreshing by mutableStateOf(false)
    private var signalsSinceRefresh = 0
    private val bandit = ShelfBandit()
    private var shelfStats: Map<String, Pair<Long, Long>> = emptyMap()
    private var shelfOrder: List<String> = emptyList()
    private val shelfImpressions = HashSet<String>()

    // ---- new users: popular songs and picking artists ----
    val discovery: DiscoverySource = discoverySource ?: YouTubeDiscovery(library)
    var popularNow by mutableStateOf<PopularNow?>(null)
    var popularLoading by mutableStateOf(false)
        private set
    var tastePicks by mutableStateOf<List<TastePick>>(emptyList())
    /** "Not now" (or a finished pick) on the Home card; remembered across launches. */
    var tastePromptDismissed by mutableStateOf(false)
    var suggestedArtists by mutableStateOf<List<ArtistItem>>(emptyList())
    var suggestedLoading by mutableStateOf(false)
    val pickerSelected = mutableStateListOf<ArtistItem>()
    var pickerSimilar by mutableStateOf<List<ArtistItem>>(emptyList())
    var pickerQuery by mutableStateOf("")
    var pickerResults by mutableStateOf<List<ArtistItem>>(emptyList())
    var pickerSearching by mutableStateOf(false)
    private var pickerSearchJob: Job? = null
    /** False until the stored mixes have been read once, so Home doesn't treat "not loaded yet" as "no mixes". */
    var mixesLoaded by mutableStateOf(false)

    // ---- keyboard shortcuts ----
    var shortcutsOpen by mutableStateOf(false)
    /** Bumped by Ctrl+L; the search box focuses itself when it changes. */
    var searchFocusTick by mutableStateOf(0)
    private var volumeBeforeMute = 0.8f

    // ---- feedback & shuffle ----
    var blocks by mutableStateOf(Blocks())
    /** Shuffle mode per play context (only non-OFF entries are kept). */
    val shuffleModes = mutableStateMapOf<String, ShuffleMode>()
    var smartLoading by mutableStateOf(false)
    /** The list the current queue was started from, in its own order (for turning shuffle off). */
    private var queueOriginal: List<SongItem> = emptyList()

    private val engine: PlayerEngine = engineFactory(::onEngineState, ::onEnded)
    private var playingSong: SongItem? = null
    private var playedMax = 0L
    private var playStartReason = StartReason.CLICK_ROW
    private var playContext = "home"
    /** Where the current queue came from ("liked", "local:<id>", "mix:<id>", ...); radio additions are "radio". */
    var queueContext = "home"
        private set
    private val radioIds = HashSet<String>()
    /** Play logging: one worker runs the writes strictly in the order plays end. */
    private val playLog = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var sideJob: Job? = null
    private var lyricsJob: Job? = null
    private var toastJob: Job? = null

    // Discord mode (declared before init, which reads the saved setting)
    var voiceDuck by mutableStateOf(VoiceDuck.OFF); private set
    /** Someone is talking in voice chat right now (while the mode is on). */
    var voiceTalking by mutableStateOf(false); private set
    val voiceDuckAvailable: Boolean get() = AppHooks.voiceMeter != null
    private var voiceMeter: AutoCloseable? = null
    @Volatile private var duckFactor = 1f
    @Volatile private var pausedByVoice = false
    @Volatile private var lastVoiceAt = 0L

    init {
        loadSettings()
        engine.setVolume(volume.toDouble())
        scope.launch { library.likedIds.collect { likedIds = it.toSet() } }
        scope.launch {
            library.mixes.collect {
                mixes = it; mixesLoaded = true
                // No mixes yet: Home needs something to show. Start the popular songs (and the picker's artists) right away.
                if (it.isEmpty()) { loadPopular(); if (!tastePromptDismissed) prefetchSuggestedArtists() }
            }
        }
        scope.launch { library.tastePicks.collect { tastePicks = it } }
        scope.launch { library.feedback.collect { blocks = Blocks.from(it) } }
        scope.launch { loadShelfStats() }
        scope.launch(Dispatchers.IO) { for (write in playLog) runCatching { write() } }
        sync.remote.player = this
        applyVoiceMeter()
        downloads.start()
        OfflineFiles.find = { id ->
            if (OfflineFiles.isLocal(id)) java.nio.file.Path.of(id.removePrefix(OfflineFiles.LOCAL_PREFIX)) else downloads.fileFor(id)?.toPath()
        }
        // Tell the other device when what plays here changes (not on every position tick).
        scope.launch {
            androidx.compose.runtime.snapshotFlow {
                listOf(queue.current?.id, queue.index, queue.items.size, playback.playing, playback.loading, playback.durationMs, volume, queue.repeat)
            }.collect { sync.remote.localChanged() }
        }
    }

    // ---------------- engine callbacks ----------------

    private fun onEngineState(s: PlayerState) {
        if (s.playing) lastPlayedAt = System.currentTimeMillis()
        if (pendingSeekMs > 0 && s.durationMs > 0 && !s.loading) { val to = pendingSeekMs; pendingSeekMs = 0; engine.seek(to); positionMs = to }
        if (pendingPause && s.playing) { pendingPause = false; engine.togglePause() }
        if (s.positionMs != positionMs) positionMs = s.positionMs
        if (s.positionMs > playedMax) playedMax = s.positionMs
        val flags = s.copy(positionMs = 0)
        if (flags != playback) { playback = flags; sync.remote.localChanged() }
    }

    private fun onEnded() = skipNext(auto = true)

    // ---------------- playback control ----------------

    /**
     * Closes the play that is ending: a taste signal for any play (a quick skip is a strong signal),
     * and a history entry if it was listened to for at least 10 s.
     */
    private fun flushPlay(endReason: String) {
        val song = playingSong ?: return
        val ms = playedMax
        val duration = playback.durationMs.takeIf { it > 0 } ?: ((song.duration ?: 0) * 1000L)
        val reason = if (playback.error != null) EndReason.ERROR else endReason
        val start = playStartReason; val context = playContext
        val at = java.time.LocalDateTime.now()
        playedMax = 0
        playingSong = null
        playLog.trySend {
            if (ms >= 10_000) library.recordPlay(song, ms)
            if (ms > 0 || reason == EndReason.FWD_BUTTON) {
                library.recordSignal(song, ms, duration, start, reason, context, at)
                onSignalRecorded()
            }
        }
    }

    /** Starts the queue's current song; [start] says why it starts, [ended] why the previous one stopped. */
    fun startCurrent(start: String = StartReason.CLICK_ROW, ended: String = EndReason.NEW_SELECTION, backwards: Boolean = false) {
        if (queue.current == null) return
        // Hidden songs and blocked artists are passed over, like Spotify does.
        val target = playableIndex(queue.index, backwards)
        if (target == null) {
            flushPlay(ended); engine.stop()
            showToast("Nothing here can play: these songs are hidden or by artists you blocked")
            return
        }
        if (target != queue.index) queue = queue.jump(target)
        val song = queue.current ?: return
        flushPlay(ended)
        playingSong = song
        playStartReason = if (song.id in queue.recommended) StartReason.SMART_SHUFFLE else start
        playContext = if (song.id in radioIds) "radio" else queueContext
        positionMs = 0
        engine.play(song.id, queue.upNext?.id)
        sync.remote.localChanged()
        if (queue.upNext == null && autoplay && !OfflineFiles.isLocal(song.id)) extendWithRadio(song, thenPlayFirstNew = false, ended = ended)
        loadSideEffects(song)
    }

    /** First playable position from [from], searching forward (or backward first for Previous). */
    private fun playableIndex(from: Int, backwards: Boolean): Int? {
        val items = queue.items
        if (from !in items.indices) return null
        val forward = (from until items.size) + if (queue.repeat == RepeatMode.ALL) (0 until from).toList() else emptyList()
        val order = if (backwards) (from downTo 0) + (from + 1 until items.size) else forward
        return order.firstOrNull { !blocks.blocked(items[it], queueContext) }
    }

    private fun loadSideEffects(song: SongItem) {
        lyricsJob?.cancel(); sideJob?.cancel()
        lyrics = LyricsUiState.Loading
        lyricsJob = scope.launch { lyrics = AppHooks.loadLyrics(song) }
        sideJob = scope.launch {
            val dominant = song.thumbnail.takeIf { it.isNotBlank() }?.let { AppHooks.dominantColor(it) }
            accent = if (dominant != null) accentFrom(dominant) else Accent.DEFAULT
        }
    }

    private fun extendWithRadio(after: SongItem, thenPlayFirstNew: Boolean, ended: String) {
        scope.launch {
            val radio = fetchRadio(after).filter { r -> queue.items.none { it.id == r.id } && !blocks.blocked(r, "radio") }
            if (radio.isEmpty()) return@launch
            radioIds += radio.map { it.id }
            val firstNew = queue.items.size
            queue = queue.copy(items = queue.items + radio)
            if (thenPlayFirstNew) { queue = queue.jump(firstNew); startCurrent(StartReason.AUTOPLAY, ended) } else engine.prefetch(radio.first().id)
        }
    }

    fun skipNext(auto: Boolean = false) {
        if (!auto && remoteControlling) { controlRemote(RemoteCommand.simple("next")); return }
        localSkipNext(auto)
    }

    private fun localSkipNext(auto: Boolean) {
        val ni = queue.nextIndex(auto)
        val reason = if (auto) EndReason.TRACK_DONE else EndReason.FWD_BUTTON
        when {
            ni != null -> { queue = queue.jump(ni); startCurrent(if (auto) StartReason.TRACK_DONE else StartReason.FWD_BUTTON, reason) }
            autoplay && queue.current != null && !OfflineFiles.isLocal(queue.current!!.id) -> extendWithRadio(queue.current!!, thenPlayFirstNew = true, ended = reason)
            else -> { flushPlay(reason); engine.stop() }
        }
    }

    fun skipPrevious() {
        if (remoteControlling) { controlRemote(RemoteCommand.simple("prev")); return }
        localSkipPrevious()
    }

    private fun localSkipPrevious() {
        if (positionMs > 3000) { localSeek(0); return }
        queue.previousIndex()?.let { queue = queue.jump(it); startCurrent(StartReason.BACK_BUTTON, EndReason.BACK_BUTTON, backwards = true) }
    }

    fun togglePause() {
        if (remoteControlling) { controlRemote(RemoteCommand.simple("toggle")); return }
        val resuming = !playback.playing
        pausedByVoice = false // the person decided; voice chat doesn't resume it
        engine.togglePause()
        if (resuming) claimActive()
    }

    fun toggleMute() {
        if (volume == 0f) changeVolume(volumeBeforeMute.coerceAtLeast(0.05f))
        else { volumeBeforeMute = volume; changeVolume(0f) }
    }

    /** Runs a keyboard shortcut (see [Shortcut]); the same actions as the buttons. */
    fun perform(shortcut: Shortcut) {
        when (shortcut) {
            Shortcut.PLAY_PAUSE -> togglePause()
            Shortcut.NEXT -> skipNext()
            Shortcut.PREVIOUS -> skipPrevious()
            Shortcut.SEEK_FORWARD -> seekBy(5_000)
            Shortcut.SEEK_BACK -> seekBy(-5_000)
            Shortcut.VOLUME_UP -> nudgeVolume(+0.1f)
            Shortcut.VOLUME_DOWN -> nudgeVolume(-0.1f)
            Shortcut.MUTE -> toggleMute()
            Shortcut.SHUFFLE -> {
                cycleShuffle()
                showToast(when (shuffleMode()) { ShuffleMode.OFF -> "Shuffle off"; ShuffleMode.ON -> "Shuffle on"; ShuffleMode.SMART -> "Smart Shuffle on" })
            }
            Shortcut.REPEAT -> {
                cycleRepeat()
                showToast(when (queue.repeat) { RepeatMode.OFF -> "Repeat off"; RepeatMode.ALL -> "Repeat all"; RepeatMode.ONE -> "Repeat this song" })
            }
            Shortcut.LIKE -> queue.current?.let { toggleLike(it) }
            Shortcut.SEARCH -> { navigate(Route.Search); searchFocusTick++ }
            Shortcut.BACK -> goBack()
            Shortcut.FORWARD -> goForward()
            Shortcut.HELP -> shortcutsOpen = !shortcutsOpen
        }
    }

    private fun seekBy(deltaMs: Long) {
        val duration = nowPlayback.durationMs
        if (nowQueue.current == null || duration <= 0) return
        seek((nowPositionMs + deltaMs).coerceIn(0L, duration))
    }

    private fun nudgeVolume(delta: Float) {
        changeVolume((volume + delta).coerceIn(0f, 1f))
        showToast("Volume ${(volume * 100).toInt()}%")
    }
    fun seek(ms: Long) { if (remoteControlling) controlRemote(RemoteCommand.seek(ms)) else localSeek(ms) }
    private fun localSeek(ms: Long) { positionMs = ms; engine.seek(ms); sync.remote.localChanged() }
    fun changeVolume(v: Float) { if (remoteControlling) controlRemote(RemoteCommand.volume(v)) else localVolume(v) }
    private fun localVolume(v: Float) { volume = v; engine.setVolume((v * duckFactor).toDouble()); sync.remote.localChanged() }

    // ---------------- Discord mode: quieter (or paused) while someone talks ----------------


    fun changeVoiceDuck(mode: VoiceDuck) {
        voiceDuck = mode
        saveSettings()
        applyVoiceMeter()
        if (mode != VoiceDuck.OFF && !voiceDuckAvailable) showToast("This only works on the computer")
    }

    private fun applyVoiceMeter() {
        if (voiceDuck == VoiceDuck.OFF) {
            runCatching { voiceMeter?.close() }; voiceMeter = null
            if (voiceTalking) endTalking()
            setDuck(1f)
        } else if (voiceMeter == null) {
            voiceMeter = runCatching { AppHooks.voiceMeter?.invoke(::onVoiceLevel) }.getOrNull()
        }
    }

    /** About 20 times a second, from the meter's thread. */
    internal fun onVoiceLevel(level: Float) {
        if (voiceDuck == VoiceDuck.OFF) return
        val now = System.currentTimeMillis()
        if (level > VOICE_THRESHOLD) {
            lastVoiceAt = now
            if (!voiceTalking) startTalking()
        } else if (voiceTalking && now - lastVoiceAt > if (voiceDuck == VoiceDuck.PAUSE) 1500 else 800) {
            endTalking()
        }
        // Down quickly, back up gently.
        val target = if (voiceTalking && voiceDuck == VoiceDuck.LOWER) DUCKED_VOLUME else 1f
        if (duckFactor != target) setDuck(if (target < duckFactor) maxOf(target, duckFactor - 0.4f) else minOf(target, duckFactor + 0.08f))
    }

    private fun setDuck(f: Float) { duckFactor = f; engine.setVolume((volume * f).toDouble()) }

    private fun startTalking() {
        voiceTalking = true
        if (voiceDuck == VoiceDuck.PAUSE && playback.playing) { pausedByVoice = true; engine.togglePause() }
    }

    private fun endTalking() {
        voiceTalking = false
        if (pausedByVoice) {
            pausedByVoice = false
            if (!playback.playing && queue.current != null) engine.togglePause()
        }
    }

    /** Plays [list] from [index]; the page it was started from becomes the play context. */
    fun playFromList(list: List<SongItem>, index: Int, context: String = routeContext(route)) {
        if (remoteControlling) {
            // Like Spotify: what you pick plays on the device that is playing.
            val shuffled = shuffleMode(context) != ShuffleMode.OFF && index in list.indices
            val ordered = if (shuffled) listOf(list[index]) + list.filterIndexed { i, _ -> i != index }.shuffled() else list
            controlRemote(RemoteCommand.queue(ordered, if (shuffled) 0 else index, context))
            val name = playingOn
            // Say where it plays, with a way to play it here instead.
            showToast("Playing on $name", ToastAction("Play here") { lastRemoteActionAt = 0; playHere() })
            return
        }
        claimActive()
        queueContext = context
        radioIds.clear()
        queueOriginal = list
        val mode = shuffleMode(context)
        queue = queue.withItems(list, index).copy(recommended = emptySet())
        if (mode != ShuffleMode.OFF && index in list.indices) {
            // The song you picked plays first; everything else (including songs listed before it) is shuffled.
            queue = queue.copy(items = listOf(list[index]) + list.filterIndexed { i, _ -> i != index }.shuffled(), index = 0)
        }
        startCurrent()
        if (mode == ShuffleMode.SMART) addSmartRecommendations()
    }

    /** A page's big play button: from the top, or from a random song when shuffle is on for that page. */
    fun playCollection(list: List<SongItem>, context: String = routeContext(route)) {
        if (list.isEmpty()) return
        playFromList(list, if (shuffleMode(context) == ShuffleMode.OFF) 0 else list.indices.random(), context)
    }

    fun playShuffled(list: List<SongItem>) {
        if (list.isEmpty()) return
        val ctx = routeContext(route)
        if (shuffleMode(ctx) == ShuffleMode.OFF) shuffleModes[ctx] = ShuffleMode.ON
        playCollection(list, ctx)
    }
    fun jumpTo(index: Int) { if (remoteControlling) controlRemote(RemoteCommand.jump(index)) else { queue = queue.jump(index); startCurrent() } }

    fun addToQueue(song: SongItem) {
        if (remoteControlling) { controlRemote(RemoteCommand.song("add", song)); showToast("Added to the queue on $playingOn"); return }
        val was = queue.items.isEmpty(); queue = queue.add(song)
        if (was) { queue = queue.jump(0); startCurrent() } else showToast("Added to queue")
    }

    fun playNext(song: SongItem) {
        if (remoteControlling) { controlRemote(RemoteCommand.song("playnext", song)); showToast("Playing next on $playingOn"); return }
        queue = queue.playNext(song)
        if (queue.items.size == 1) { queue = queue.jump(0); startCurrent() } else { queue.upNext?.let { engine.prefetch(it.id) }; showToast("Playing next") }
    }

    fun removeFromQueue(position: Int) {
        if (remoteControlling) { controlRemote(kotlinx.serialization.json.buildJsonObject { put("t", "remove"); put("index", position) }); return }
        queue = queue.remove(position); sync.remote.localChanged()
    }

    /** Drag to reorder: moves the song at [from] to [to] in the queue. */
    fun moveInQueue(from: Int, to: Int) {
        if (remoteControlling) { controlRemote(kotlinx.serialization.json.buildJsonObject { put("t", "move"); put("from", from); put("to", to) }); return }
        queue = queue.move(from, to)
        queue.upNext?.let { engine.prefetch(it.id) }
        sync.remote.localChanged()
    }
    fun shuffleQueue() { queue = queue.shuffledUpcoming(); queue.upNext?.let { engine.prefetch(it.id) } }
    fun clearQueue() {
        flushPlay(EndReason.CLOSED); engine.stop(); queue = QueueState(); lyrics = LyricsUiState.Idle
        accent = Accent.DEFAULT
    }
    // ---------------- shuffle & Smart Shuffle ----------------

    fun shuffleMode(context: String = queueContext): ShuffleMode = shuffleModes[context] ?: ShuffleMode.OFF

    /** Smart Shuffle is for collections (playlists, albums, mixes, liked songs), not radio or search results. */
    fun smartAvailable(context: String): Boolean =
        context == "liked" || context == "library" || listOf("local:", "playlist:", "album:", "mix:").any { context.startsWith(it) }

    /** Shuffle button: off -> shuffle -> Smart Shuffle -> off (Smart only where it applies). */
    fun cycleShuffle(context: String = queueContext) {
        val next = when (shuffleMode(context)) {
            ShuffleMode.OFF -> ShuffleMode.ON
            ShuffleMode.ON -> if (smartAvailable(context)) ShuffleMode.SMART else ShuffleMode.OFF
            ShuffleMode.SMART -> ShuffleMode.OFF
        }
        setShuffle(context, next)
    }

    fun setShuffle(context: String, mode: ShuffleMode) {
        if (mode == ShuffleMode.OFF) shuffleModes.remove(context) else shuffleModes[context] = mode
        if (context != queueContext || queue.items.isEmpty()) return // applies next time this list plays
        when (mode) {
            ShuffleMode.OFF -> {
                val had = queue.recommended.isNotEmpty()
                queue = queue.linear(queueOriginal)
                if (had) showToast("Removed Smart Shuffle recommendations")
            }
            ShuffleMode.ON -> queue = queue.withoutRecommendations().shuffledUpcoming()
            ShuffleMode.SMART -> addSmartRecommendations()
        }
        queue.upNext?.let { engine.prefetch(it.id) }
    }

    /** Fetches today's recommendations for the current list and mixes them in (one after every three songs). */
    private fun addSmartRecommendations() {
        val ctx = queueContext
        val songs = queueOriginal
        smartLoading = true
        scope.launch(Dispatchers.IO) {
            val count = ((songs.size + 2) / 3).coerceIn(1, 30)
            val recs = runCatching { recommender.smartShuffle(ctx, songs, count) }.getOrDefault(emptyList())
            smartLoading = false
            if (queueContext != ctx || shuffleMode(ctx) != ShuffleMode.SMART) return@launch
            queue = queue.withSmartShuffle(recs.filter { !blocks.blocked(it, ctx) })
            queue.upNext?.let { engine.prefetch(it.id) }
            val added = queue.recommended.size
            showToast(
                when (added) {
                    0 -> "Couldn't find Smart Shuffle recommendations right now, so this is a plain shuffle"
                    1 -> "Added 1 Smart Shuffle recommendation to the queue"
                    else -> "Added $added Smart Shuffle recommendations to the queue"
                },
            )
        }
    }

    /** Queue row: drop a recommendation, and don't suggest it for this list again. */
    fun removeRecommendation(position: Int) {
        val song = queue.items.getOrNull(position)?.takeIf { it.id in queue.recommended } ?: return
        val ctx = queueContext
        queue = queue.remove(position).let { it.copy(recommended = it.recommended - song.id) }
        scope.launch { library.addFeedback(song.id, Feedback.Kind.HIDE_IN_CONTEXT, ctx) }
        showToast("Removed “${song.title}”")
    }

    /** Queue row: keep a recommendation by adding it to the playlist it was recommended for. */
    fun addRecommendationToPlaylist(position: Int) {
        val song = queue.items.getOrNull(position)?.takeIf { it.id in queue.recommended } ?: return
        val playlistId = queueContext.removePrefix("local:").takeIf { queueContext.startsWith("local:") } ?: return
        queue = queue.copy(recommended = queue.recommended - song.id)
        queueOriginal = queueOriginal + song
        scope.launch { library.addToPlaylist(playlistId, song); showToast("Added “${song.title}” to the playlist") }
    }

    /** True when the current queue's recommendations can be saved to a playlist (only your own playlists). */
    val canKeepRecommendations: Boolean get() = queueContext.startsWith("local:")

    // ---------------- feedback ----------------

    fun notInterested(song: SongItem) {
        scope.launch { library.addFeedback(song.id, Feedback.Kind.NOT_INTERESTED) }
        queue = queue.withoutUpcoming { it.id == song.id }
        showToast("You won't get “${song.title}” in recommendations", ToastAction("Undo") {
            scope.launch { library.removeFeedback(song.id, Feedback.Kind.NOT_INTERESTED) }
        })
    }

    /** Label for "hide here", or null where hiding makes no sense (search, history, Liked Songs...). */
    fun hideLabel(context: String): String? = when {
        context.startsWith("local:") || context.startsWith("playlist:") -> "Hide from this playlist"
        context.startsWith("mix:") -> "Hide from this mix"
        context.startsWith("album:") -> "Hide from this album"
        else -> null
    }

    fun hideInContext(song: SongItem, context: String) {
        scope.launch { library.addFeedback(song.id, Feedback.Kind.HIDE_IN_CONTEXT, context) }
        if (queueContext == context) {
            queue = queue.withoutUpcoming { it.id == song.id }
            if (queue.current?.id == song.id) skipNext()
        }
        showToast("“${song.title}” is hidden here", ToastAction("Undo") {
            scope.launch { library.removeFeedback(song.id, Feedback.Kind.HIDE_IN_CONTEXT, context) }
        })
    }

    fun banArtist(song: SongItem) {
        val key = song.artistKey()
        val name = song.artists.firstOrNull()?.name ?: "this artist"
        scope.launch { library.addFeedback(key, Feedback.Kind.BAN_ARTIST) }
        queue = queue.withoutUpcoming { it.artistKey() == key }
        if (queue.current?.artistKey() == key) skipNext()
        showToast("We won't play $name", ToastAction("Undo") {
            scope.launch { library.removeFeedback(key, Feedback.Kind.BAN_ARTIST) }
        })
    }

    fun recommendAgain(song: SongItem) {
        scope.launch { library.removeFeedback(song.id, Feedback.Kind.NOT_INTERESTED) }
        showToast("“${song.title}” can be recommended again")
    }

    fun allowArtist(song: SongItem) {
        scope.launch { library.removeFeedback(song.artistKey(), Feedback.Kind.BAN_ARTIST) }
        showToast("${song.artists.firstOrNull()?.name ?: "This artist"} can play again")
    }

    fun unhideInContext(song: SongItem, context: String) {
        scope.launch { library.removeFeedback(song.id, Feedback.Kind.HIDE_IN_CONTEXT, context) }
        showToast("“${song.title}” is back")
    }

    fun toggleExcludeFromTaste(song: SongItem) {
        if (song.id in blocks.excluded) {
            scope.launch { library.removeFeedback(song.id, Feedback.Kind.EXCLUDE_FROM_TASTE) }
            showToast("“${song.title}” counts toward your taste again")
        } else {
            scope.launch { library.addFeedback(song.id, Feedback.Kind.EXCLUDE_FROM_TASTE) }
            showToast("“${song.title}” will barely affect your recommendations", ToastAction("Undo") {
                scope.launch { library.removeFeedback(song.id, Feedback.Kind.EXCLUDE_FROM_TASTE) }
            })
        }
    }

    fun cycleRepeat() { if (remoteControlling) controlRemote(RemoteCommand.simple("repeat")) else localCycleRepeat() }
    private fun localCycleRepeat() { queue = queue.copy(repeat = RepeatMode.entries[(queue.repeat.ordinal + 1) % RepeatMode.entries.size]); sync.remote.localChanged() }

    // ---------------- remote control and handoff (like Spotify Connect) ----------------

    private var lastPlayedAt = 0L
    private var pendingSeekMs = 0L
    private var pendingPause = false

    /** The paired device's playback while it is connected. */
    val remoteDevice: RemoteState? get() = sync.remote.other?.takeIf { sync.remote.connected }

    private fun localIdleMs(): Long = when {
        playback.playing -> 0
        lastPlayedAt == 0L -> Long.MAX_VALUE / 4
        else -> System.currentTimeMillis() - lastPlayedAt
    }

    /** When this device last sent the other one a control (play, pause, next, a song, ...), or chose it in Devices. */
    @Volatile private var lastRemoteActionAt = 0L

    /** Sends a control to the other device; while used like that, it stays the one these buttons control. */
    private fun controlRemote(command: kotlinx.serialization.json.JsonObject) {
        lastRemoteActionAt = System.currentTimeMillis()
        sync.remote.send(command)
    }

    /**
     * True while the other device is the active one: it is playing, or this device was used as its remote in the
     * last 10 minutes (so a pause from here can be undone from here). Then the player shows it, and the buttons and
     * song picks control it. A paused computer does not take over the phone's taps by itself.
     */
    val remoteControlling: Boolean
        get() {
            val o = remoteDevice ?: return false
            if (o.current == null || playback.playing) return false
            if (o.playing || o.loading) return true
            return System.currentTimeMillis() - lastRemoteActionAt < 10 * 60_000L && o.idleNow() < 10 * 60_000L
        }

    /** "Redmi Note 8" while controlling it ("Playing on …"). */
    val playingOn: String? get() = if (remoteControlling) remoteDevice?.name else null

    // What the player shows: this device, or the other one while controlling it.
    val nowQueue: QueueState
        get() = remoteDevice?.takeIf { remoteControlling }?.let { o ->
            QueueState(o.items, o.index, runCatching { RepeatMode.valueOf(o.repeat) }.getOrDefault(RepeatMode.OFF))
        } ?: queue
    val nowPlayback: PlayerState
        get() = remoteDevice?.takeIf { remoteControlling }?.let { PlayerState(loading = it.loading, playing = it.playing, durationMs = it.durationMs) } ?: playback
    val nowPositionMs: Long get() = if (remoteControlling) sync.remote.otherPositionMs else positionMs
    val nowVolume: Float get() = remoteDevice?.takeIf { remoteControlling }?.volume ?: volume

    /** Only one device plays at a time: starting here pauses the other one. */
    private fun claimActive() {
        if (remoteDevice?.playing == true) sync.remote.send(RemoteCommand.simple("pause"))
    }

    /** Moves what this device plays (queue, song, position) to the other device. */
    fun playOnOtherDevice() {
        val o = remoteDevice ?: return
        val current = queue.current ?: return
        if (OfflineFiles.isLocal(current.id)) { showToast("This song's file is only on this device, so it can't play on ${o.name}"); return }
        // Files that are only on this device can't play there; the rest of the queue goes.
        val items = queue.items.filter { !OfflineFiles.isLocal(it.id) }
        controlRemote(RemoteCommand.queue(items, items.indexOfFirst { it.id == current.id }, queueContext, positionMs, playing = true, repeat = queue.repeat.name))
        if (playback.playing) engine.togglePause()
        showToast("Playing on ${o.name}")
    }

    /** Takes over what the other device plays and continues here. */
    fun playHere() {
        val o = remoteDevice ?: return
        if (o.current == null) return
        lastRemoteActionAt = 0 // this device plays now; its buttons are its own again
        startQueue(o.items, o.index, o.context.ifEmpty { "remote" }, o.positionNow(), playing = true, repeat = o.repeat)
        sync.remote.send(RemoteCommand.simple("pause"))
    }

    private fun startQueue(items: List<SongItem>, index: Int, context: String, positionMs: Long, playing: Boolean, repeat: String) {
        if (index !in items.indices) return
        queueContext = context
        radioIds.clear()
        queueOriginal = items
        queue = QueueState(items, index, runCatching { RepeatMode.valueOf(repeat) }.getOrDefault(RepeatMode.OFF))
        pendingSeekMs = positionMs.coerceAtLeast(0)
        pendingPause = !playing
        startCurrent()
    }

    override fun remoteSnapshot(): RemoteState {
        // A long queue is cut to the part around the current song.
        val from = (queue.index - 50).coerceAtLeast(0)
        val items = queue.items.drop(from).take(250)
        return RemoteState(
            deviceId = sync.myId, name = AppHooks.deviceName(), items = items, index = queue.index - from, repeat = queue.repeat.name,
            playing = playback.playing, loading = playback.loading, positionMs = positionMs, durationMs = playback.durationMs,
            volume = volume, idleMs = localIdleMs(), context = queueContext,
        )
    }

    /** A command from the other device; it runs here and is never sent on. */
    override fun runRemote(command: kotlinx.serialization.json.JsonObject) {
        fun str(k: String) = (command[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        when (str("t")) {
            "toggle" -> if (queue.current != null) engine.togglePause()
            "play" -> if (!playback.playing && queue.current != null) engine.togglePause()
            "pause" -> if (playback.playing) engine.togglePause()
            "next" -> localSkipNext(false)
            "prev" -> localSkipPrevious()
            "seek" -> str("ms")?.toLongOrNull()?.let { localSeek(it) }
            "volume" -> str("v")?.toFloatOrNull()?.let { localVolume(it.coerceIn(0f, 1f)) }
            "jump" -> str("index")?.toIntOrNull()?.takeIf { it in queue.items.indices }?.let { queue = queue.jump(it); startCurrent() }
            "repeat" -> localCycleRepeat()
            "move" -> { val from = str("from")?.toIntOrNull(); val to = str("to")?.toIntOrNull(); if (from != null && to != null) { queue = queue.move(from, to); queue.upNext?.let { engine.prefetch(it.id) } } }
            "remove" -> str("index")?.toIntOrNull()?.takeIf { it != queue.index }?.let { queue = queue.remove(it) }
            "add", "playnext" -> RemoteCommand.song(command)?.let { song ->
                val was = queue.items.isEmpty()
                queue = if (str("t") == "add") queue.add(song) else queue.playNext(song)
                if (was) { queue = queue.jump(0); startCurrent() }
            }
            "queue" -> startQueue(
                RemoteCommand.items(command), str("index")?.toIntOrNull() ?: 0, str("context") ?: "remote",
                str("pos")?.toLongOrNull() ?: 0, str("playing") != "false", str("repeat") ?: "OFF",
            )
        }
        sync.remote.localChanged()
    }

    // ---------------- home feed ----------------

    /** Loads the YouTube Music home feed (optionally filtered by a mood chip) plus new releases and moods. */
    fun loadHome(chip: HomePage.Chip? = null) {
        homeChip = chip
        shelfOrder = emptyList(); shelfImpressions.clear()
        scope.launch { loadShelfStats() }
        homeLoading = true; homeError = null
        homeRetryJob?.cancel()
        scope.launch {
            YouTube.home(browseId = chip?.endpoint?.browseId ?: "FEmusic_home", params = chip?.endpoint?.params)
                .onSuccess { page ->
                    homeSections = page.sections
                    homeMore = page.continuation
                    if (!page.chips.isNullOrEmpty()) homeChips = page.chips!!
                    homeRetries = 0
                    if (mixesLoaded && mixes.isEmpty()) loadPopular() // the connection is back: fill any gap
                }
                .onFailure { homeError = friendlyError(it); scheduleHomeRetry(chip) }
            homeLoading = false
        }
        if (newReleases.isEmpty() && moods.isEmpty()) scope.launch {
            YouTube.explore().onSuccess { e -> newReleases = e.newReleaseAlbums; moods = e.moodAndGenres }
        }
    }

    private var homeRetryJob: Job? = null
    private var homeRetries = 0

    /** A failed feed tries again by itself (15 s, 30 s, 60 s, 2 min), so Home fills in as soon as YouTube is back. */
    private fun scheduleHomeRetry(chip: HomePage.Chip?) {
        if (homeRetries >= 4) return
        val wait = 15_000L shl homeRetries++
        homeRetryJob = scope.launch {
            delay(wait)
            if (homeSections.isEmpty()) loadHome(chip)
        }
    }

    fun selectChip(chip: HomePage.Chip) = loadHome(if (homeChip?.title == chip.title) null else chip)

    fun loadMoreHome() {
        val token = homeMore ?: return
        if (homeLoadingMore) return
        homeLoadingMore = true
        scope.launch {
            YouTube.home(continuation = token)
                .onSuccess { page ->
                    val known = homeSections.map { it.title }.toSet()
                    homeSections = homeSections + page.sections.filter { it.title !in known }
                    homeMore = page.continuation
                }
                .onFailure { homeMore = null }
            homeLoadingMore = false
        }
    }

    // ---------------- navigation ----------------

    /** Opens [to] in the main view. Re-opening the current page does nothing. */
    fun navigate(to: Route) {
        if (route == to) return
        backStack = backStack + to
        forwardStack = emptyList()
    }

    fun goBack() {
        if (!canGoBack) return
        forwardStack = listOf(route) + forwardStack
        backStack = backStack.dropLast(1)
    }

    fun goForward() {
        val next = forwardStack.firstOrNull() ?: return
        forwardStack = forwardStack.drop(1)
        backStack = backStack + next
    }

    /** Home button: Home without wiping history (like a browser's home button). */
    fun goHome() = navigate(Route.Home)

    /** Lyrics live in the main view; the player bar button toggles them. */
    fun toggleLyrics() = if (route == Route.Lyrics) goBack() else navigate(Route.Lyrics)

    /** Player bar buttons for the right panel: open on [tab], or close it if it already shows [tab]. */
    fun togglePanel(tab: PanelTab) {
        if (panelOpen && panelTab == tab) panelOpen = false else { panelTab = tab; panelOpen = true }
    }

    /** Shows [ui] as an online page (also used by tests to show a page without the network). */
    fun showDetail(key: String, ui: DetailUi) { details[key] = ui; navigate(Route.Online(key)) }

    private fun loadDetail(key: String, title: String, load: suspend () -> DetailUi) {
        details[key] = DetailUi.Loading(title)
        navigate(Route.Online(key))
        scope.launch { details[key] = runCatching { load() }.getOrElse { DetailUi.Failed(title, friendlyError(it)) } }
    }

    fun openPlaylist(p: PlaylistItem) = loadDetail("playlist:${p.id}", p.title) {
        val page = YouTube.playlist(p.id).getOrThrow()
        val songs = page.songs.toMutableList()
        var next = page.songsContinuation ?: page.continuation
        var pages = 0
        while (next != null && pages < 4) { // long playlists: pull a few more pages
            val more = YouTube.playlistContinuation(next).getOrNull() ?: break
            songs += more.songs; next = more.continuation; pages++
        }
        DetailUi.Songs(
            page.playlist.title.ifBlank { p.title }, "Playlist",
            listOfNotNull(page.playlist.author?.name, "${songs.size} songs").joinToString(" • "),
            page.playlist.thumbnail ?: p.thumbnail, songs.distinctBy { it.id },
        )
    }

    fun openAlbum(a: AlbumItem) = loadDetail("album:${a.browseId}", a.title) {
        val page = YouTube.album(a.browseId).getOrThrow()
        DetailUi.Songs(
            page.album.title.ifBlank { a.title }, "Album",
            listOfNotNull(page.album.artists?.joinToString { it.name }, page.album.year?.toString(), "${page.songs.size} songs").joinToString(" • "),
            page.album.thumbnail.ifBlank { a.thumbnail }, page.songs,
        )
    }

    fun openArtist(a: ArtistItem) = loadDetail("artist:${a.id}", a.title) {
        val page = YouTube.artist(a.id).getOrThrow()
        DetailUi.Collection(page.artist.title.ifBlank { a.title }, page.sections.filter { it.items.isNotEmpty() }.map { BrowseResult.Item(it.title, it.items) })
    }

    fun openMood(item: MoodAndGenres.Item) = loadDetail("mood:${item.endpoint.browseId}:${item.endpoint.params}", item.title) {
        val r = YouTube.browse(item.endpoint.browseId, item.endpoint.params).getOrThrow()
        DetailUi.Collection(r.title ?: item.title, r.items.filter { it.items.isNotEmpty() })
    }

    /** Click on a card: open playlists/albums, play songs from their shelf, search for artists. */
    fun openItem(item: YTItem, siblings: List<YTItem>) {
        when (item) {
            is PlaylistItem -> openPlaylist(item)
            is AlbumItem -> openAlbum(item)
            is SongItem -> { val songs = siblings.filterIsInstance<SongItem>(); playFromList(songs, songs.indexOfFirst { it.id == item.id }.coerceAtLeast(0)) }
            is ArtistItem -> openArtist(item)
            else -> Unit
        }
    }

    /** The round play button on a card: start playing right away without opening the page. */
    fun playItem(item: YTItem, siblings: List<YTItem>) {
        when (item) {
            is PlaylistItem -> scope.launch {
                showToast("Loading “${item.title}”…")
                YouTube.playlist(item.id).onSuccess { if (it.songs.isNotEmpty()) playFromList(it.songs, 0) }.onFailure { showToast("Couldn't load that playlist") }
            }
            is AlbumItem -> scope.launch {
                showToast("Loading “${item.title}”…")
                YouTube.album(item.browseId).onSuccess { if (it.songs.isNotEmpty()) playFromList(it.songs, 0) }.onFailure { showToast("Couldn't load that album") }
            }
            else -> openItem(item, siblings)
        }
    }

    /** "Mix": the song you picked followed by YouTube Music's radio of similar songs. */
    fun playMix(seed: SongItem) {
        scope.launch {
            showToast("Building your mix…")
            val radio = fetchRadio(seed)
            if (radio.isEmpty()) { showToast("Couldn't build a mix right now"); return@launch }
            playFromList(listOf(seed) + radio.take(49), 0, context = "radio")
        }
    }

    fun saveAsPlaylist(name: String, songs: List<SongItem>) {
        scope.launch {
            val p = library.createPlaylist(name)
            songs.forEach { library.addToPlaylist(p.id, it) }
            showToast("Saved “${p.name}” to your playlists")
        }
    }

    // ---------------- recommendations ----------------

    /** Rebuilds whatever mixes are due (in the background); [force] rebuilds all of them. */
    fun refreshMixes(force: Boolean = false) {
        if (mixesRefreshing) { if (force) refreshAgain = true; return } // a forced rebuild waits for the running one
        mixesRefreshing = true
        signalsSinceRefresh = 0
        scope.launch(Dispatchers.IO) {
            runCatching { recommender.refresh(force) }.onFailure { println("[reco] refresh failed: ${it.message}") }
            mixesRefreshing = false
            if (refreshAgain) { refreshAgain = false; refreshMixes(force = true) }
        }
    }
    private var refreshAgain = false

    // ---------------- new users: popular songs and picking artists ----------------

    /** Loads "Popular right now" (YouTube Music's hit playlist) once; Home shows it until there are mixes. */
    fun loadPopular() {
        if (popularNow != null || popularLoading) return
        popularLoading = true
        scope.launch(Dispatchers.IO) {
            // A flaky connection should not leave Home bare: try a few times before giving up.
            for (attempt in 1..3) {
                popularNow = runCatching { discovery.popularNow() }.getOrNull()
                if (popularNow != null) break
                delay(1_500L * attempt)
            }
            popularLoading = false
        }
    }

    /** Opens the artist picker, with the current picks already selected. */
    fun openPicker() {
        pickerSelected.clear()
        pickerSelected += tastePicks.map { ArtistItem(id = it.artistKey, title = it.name, thumbnail = it.thumbnail.ifBlank { null }, shuffleEndpoint = null, radioEndpoint = null) }
        pickerQuery = ""; pickerResults = emptyList(); pickerSimilar = emptyList()
        navigate(Route.Picker)
        prefetchSuggestedArtists()
    }

    /** Looks up the picker's starter artists (about 5 s the first time), so Home can have them ready before anyone opens the picker. */
    fun prefetchSuggestedArtists() {
        if (suggestedArtists.isNotEmpty() || suggestedLoading) return
        suggestedLoading = true
        scope.launch(Dispatchers.IO) {
            suggestedArtists = runCatching { discovery.suggestedArtists() }.getOrDefault(emptyList())
            suggestedLoading = false
        }
    }

    /** Picks or un-picks an artist; picking one suggests artists fans of it also like. */
    fun togglePick(artist: ArtistItem) {
        val at = pickerSelected.indexOfFirst { it.id == artist.id }
        if (at >= 0) { pickerSelected.removeAt(at); return }
        pickerSelected += artist
        scope.launch(Dispatchers.IO) {
            val similar = runCatching { discovery.similarArtists(artist.id) }.getOrDefault(emptyList())
            val have = (pickerSelected.map { it.id } + pickerSimilar.map { it.id } + suggestedArtists.map { it.id }).toSet()
            pickerSimilar = (pickerSimilar + similar.filter { it.id !in have }).take(24)
        }
    }

    fun searchPickerArtists(query: String) {
        pickerQuery = query
        pickerSearchJob?.cancel()
        if (query.isBlank()) { pickerResults = emptyList(); pickerSearching = false; return }
        pickerSearching = true
        pickerSearchJob = scope.launch(Dispatchers.IO) {
            delay(300) // wait for a pause in typing
            pickerResults = runCatching { discovery.searchArtists(query) }.getOrDefault(emptyList())
            pickerSearching = false
        }
    }

    /** Done: keep the picks, hide the Home card and build the first mixes from them right away. */
    fun savePicks() {
        if (pickerSelected.isEmpty()) return
        val chosen = pickerSelected.toList()
        tastePromptDismissed = true
        scope.launch {
            val now = java.time.LocalDateTime.now()
            library.replaceTastePicks(chosen.map { TastePick(it.id, it.title, it.thumbnail.orEmpty(), now) })
            recommender.invalidateEmpty()
            refreshMixes(force = true)
        }
        showToast("Thanks! Building your first mixes now")
        leavePicker()
    }

    /** Skip: nothing is saved; the Home card stays away. */
    fun skipPicker() { tastePromptDismissed = true; leavePicker() }

    fun dismissTastePrompt() { tastePromptDismissed = true }

    private fun leavePicker() { if (route == Route.Picker) { if (canGoBack) goBack() else navigate(Route.Home) } }

    /** True while the taste-based mixes don't exist yet (a new user): worth trying again sooner. */
    private fun tasteMixesMissing() = listOf(MixKind.DAILY, MixKind.DISCOVER).any { k -> mixes.none { it.kind == k } }

    private fun onSignalRecorded() {
        // Plenty of new listening: let the mixes catch up without waiting for tomorrow. A user who has
        // no taste mixes yet gets them after just a few plays.
        if (++signalsSinceRefresh >= (if (tasteMixesMissing()) Recommender.RETRY_AFTER_SIGNALS else 20)) refreshMixes()
    }

    fun mixSongs(mix: GeneratedMix): List<SongItem> = TrackCodec.decode(mix.songs)

    fun playGeneratedMix(mix: GeneratedMix) {
        val songs = mixSongs(mix)
        if (songs.isNotEmpty()) playFromList(songs, 0, context = "mix:${mix.id}")
    }

    private suspend fun loadShelfStats() {
        runCatching { shelfStats = library.shelfStats().mapValues { it.value.impressions to it.value.clicks } }
    }

    /**
     * Home shelf order for this visit, from the bandit. Fixed once drawn (so the page doesn't shuffle
     * while you look at it); shelves that appear later (infinite scroll) are ranked among themselves
     * and added at the end.
     */
    fun orderShelves(keys: List<String>): List<String> {
        val fresh = keys.filter { it !in shelfOrder }
        if (fresh.isNotEmpty()) shelfOrder = shelfOrder + bandit.order(fresh, shelfStats)
        return shelfOrder.filter { it in keys }
    }

    /** Counts each shelf once per Home visit. */
    fun noteShelfImpressions(keys: List<String>) {
        val new = keys.filter { shelfImpressions.add(it) }
        if (new.isNotEmpty()) scope.launch { runCatching { library.recordShelfImpressions(new) } }
    }

    fun noteShelfClick(key: String) { scope.launch { runCatching { library.recordShelfClick(key) } } }

    // ---------------- search ----------------

    /** The search box changed: remember the text and (after a short pause) fetch matches for it. */
    fun typeQuery(text: String) {
        query = text
        val q = text.trim()
        suggestJob?.cancel()
        if (q.isEmpty()) { suggestions = null; suggestionsFor = ""; suggestionsLoading = false; return }
        suggestionsLoading = true
        suggestJob = scope.launch {
            delay(200) // wait for a pause in typing
            YouTube.searchSuggestions(q)
                .onSuccess { suggestions = it; suggestionsFor = q }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) AppHooks.log("Suggestions for '$q' failed: ${it.message?.take(120)}") }
            suggestionsLoading = false
        }
    }

    fun search(q: String = query) {
        query = q
        if (q.isBlank()) return
        navigate(Route.Search)
        searching = true; searchError = null; searchDone = false
        searchTab = SearchTab.All; tabCache.clear(); tabItems = emptyList(); tabMore = false; tabContinuation = null
        searchedQuery = q; lastSearched = q.trim(); suggestJob?.cancel(); suggestionsLoading = false
        val gen = ++searchGeneration
        scope.launch {
            runCatching { library.rememberSearch(q) }
            var failure: Throwable? = null
            YouTube.searchSummary(q)
                .onSuccess { page ->
                    if (gen != searchGeneration) return@launch
                    val ranked = SearchRank.apply(q, page.summaries) // the song that was typed first, not a video or cover of it
                    searchSummaries = ranked.summaries
                    results = ranked.songs
                }
                .onFailure { failure = it }
            if (failure != null) { // the mixed page failed: fall back to the plain song list so search still works
                YouTube.search(q, YouTube.SearchFilter.FILTER_SONG)
                    .onSuccess { r -> if (gen != searchGeneration) return@launch; searchSummaries = emptyList(); results = SearchRank.rank(q, r.items.filterIsInstance<SongItem>()); failure = null }
                    .onFailure { failure = it }
            }
            if (gen != searchGeneration) return@launch
            failure?.let { AppHooks.log("Search for '$q' failed: ${it.message?.take(200)}"); searchError = friendlyError(it) }
            searching = false; searchDone = true
        }
    }

    private fun filtersFor(tab: SearchTab): List<YouTube.SearchFilter> = when (tab) {
        SearchTab.Songs -> listOf(YouTube.SearchFilter.FILTER_SONG)
        SearchTab.Videos -> listOf(YouTube.SearchFilter.FILTER_VIDEO)
        SearchTab.Albums -> listOf(YouTube.SearchFilter.FILTER_ALBUM)
        SearchTab.Artists -> listOf(YouTube.SearchFilter.FILTER_ARTIST)
        SearchTab.Playlists -> listOf(YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST, YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST)
        SearchTab.All -> emptyList()
    }

    /** Switches the result view; filtered lists are fetched on first use and kept for the rest of this search. */
    fun selectSearchTab(tab: SearchTab) {
        searchTab = tab
        if (tab == SearchTab.All) return
        tabCache[tab]?.let { (items, cont) -> tabItems = items; tabContinuation = cont; tabMore = cont != null; tabLoading = false; return }
        tabItems = emptyList(); tabContinuation = null; tabMore = false; tabLoading = true
        val gen = searchGeneration; val q = searchedQuery
        scope.launch {
            val items = mutableListOf<YTItem>()
            var cont: String? = null
            var failed = false
            for (f in filtersFor(tab)) {
                YouTube.search(q, f).onSuccess { items += it.items; cont = it.continuation }.onFailure { failed = true }
            }
            if (gen != searchGeneration || tab != searchTab) return@launch
            val unique = items.distinctBy { it.id }.let { list ->
                if (tab == SearchTab.Songs || tab == SearchTab.Videos) SearchRank.rank(q, list.filterIsInstance<SongItem>()) else list
            }
            if (failed && unique.isEmpty()) showToast("Couldn't load ${tab.label.lowercase()}. Check your connection.") else tabCache[tab] = unique to cont
            tabItems = unique; tabContinuation = cont; tabMore = cont != null; tabLoading = false
        }
    }

    /** "Show more" under a filtered list: the next page of YouTube Music's results. */
    fun loadMoreSearch() {
        val cont = tabContinuation ?: return
        if (tabLoading) return
        tabLoading = true
        val gen = searchGeneration; val tab = searchTab
        scope.launch {
            YouTube.searchContinuation(cont)
                .onSuccess { r ->
                    if (gen != searchGeneration || tab != searchTab) return@onSuccess
                    tabItems = (tabItems + r.items).distinctBy { it.id }
                    tabContinuation = r.continuation; tabMore = r.continuation != null
                    tabCache[tab] = tabItems to tabContinuation
                }
                .onFailure { showToast("Couldn't load more results") }
            tabLoading = false
        }
    }

    // ---------------- library ----------------

    fun toggleLike(song: SongItem) {
        scope.launch {
            library.toggleLike(song)
            if (tasteMixesMissing()) refreshMixes() // the first likes are enough to start Discover Weekly
        }
    }

    fun addToPlaylist(playlistId: String, song: SongItem) {
        scope.launch { showToast(if (library.addToPlaylist(playlistId, song)) "Added to playlist" else "Already in that playlist") }
    }

    fun createPlaylist(name: String) {
        val song = newPlaylistFor
        newPlaylistOpen = false; newPlaylistFor = null
        scope.launch {
            val p = library.createPlaylist(name)
            if (song != null) library.addToPlaylist(p.id, song)
            showToast("Created “${p.name}”")
        }
    }

    fun importAndroid(file: File) {
        importUi = ImportUi.Running(file.name)
        scope.launch {
            importUi = runCatching { library.importAndroid(file) }
                .fold({ ImportUi.Done(it) }, { ImportUi.Failed(it.message ?: it.toString()) })
            if (importUi is ImportUi.Done) { // imported likes, playlists and plays change taste a lot
                recommender.invalidateEmpty()
                refreshMixes(force = true)
            }
        }
    }

    private var spotifyImportJob: Job? = null

    /** Imports a public Spotify playlist or album from a pasted link. */
    fun importSpotifyLink(text: String) = runSpotifyImport("Spotify link", "Opening the Spotify page…") { SpotifyLink.fetch(text) }

    /** Imports song links copied from the Spotify desktop app (Ctrl+A, Ctrl+C) into Liked Songs or a new playlist. */
    fun importSpotifySongs(text: String, asLiked: Boolean, playlistName: String) {
        val ids = SpotifyLink.trackIds(text)
        runSpotifyImport("Spotify songs", "Reading ${ids.size} songs from Spotify…") { step ->
            SpotifyLink.fetchTracks(ids, asLiked, playlistName) { done, total -> step("Reading songs from Spotify: $done of $total") }
        }
    }

    /** Imports the Spotify data download (.zip) or one of its .json files; [read] gives the file's bytes. */
    fun importSpotifyFile(name: String, read: () -> ByteArray) =
        runSpotifyImport(name, "Reading $name…") { SpotifyFiles.read(name, read()) }

    private fun runSpotifyImport(source: String, firstStep: String, load: suspend (step: (String) -> Unit) -> SpotifyExport) {
        spotifyImportOpen = false
        spotifyImportJob?.cancel()
        importUi = ImportUi.Running(source, firstStep, cancellable = true)
        spotifyImportJob = scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val export = load { importUi = ImportUi.Running(source, it, cancellable = true) }
                SpotifyImporter(library).import(export) { done, total ->
                    importUi = ImportUi.Running(source, "Finding your songs on YouTube Music: $done of $total", cancellable = true)
                }
            }
            if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
            importUi = result.fold(
                { ImportUi.Message("Spotify import complete", it.text()) },
                { AppHooks.log("Spotify import failed: ${it.message?.take(200)}"); ImportUi.Failed(it.message ?: "The Spotify import didn't work") },
            )
            if (result.isSuccess) { recommender.invalidateEmpty(); refreshMixes(force = true) } // the new songs change taste
        }
    }

    fun cancelSpotifyImport() {
        spotifyImportJob?.cancel()
        importUi = ImportUi.Idle
        showToast("Import stopped")
    }

    fun showToast(text: String, action: ToastAction? = null) {
        toast = text
        toastAction = action
        toastJob?.cancel()
        toastJob = scope.launch { delay(if (action != null) 5000 else 2400); toast = null; toastAction = null }
    }

    /** The toast's button was clicked. */
    fun runToastAction() {
        val action = toastAction ?: return
        toast = null; toastAction = null; toastJob?.cancel()
        action.run()
    }

    // ---------------- lifecycle ----------------

    /** Looks for a newer release (on start; [manual] from the top bar button also says when there is none). */
    fun checkForUpdates(manual: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val found = runCatching { AppHooks.updates?.check() }.onFailure { AppHooks.log("Update check failed: ${it.message?.take(120)}") }.getOrNull()
            if (found != null) { update = found; updateDialogOpen = true }
            else if (manual) showToast("You're up to date (version ${(AppHooks.updates?.currentVersion() ?: "")})")
        }
    }

    /** Downloads the installer, checks it, starts it and closes the app. */
    fun installUpdate() {
        val info = update ?: return
        if (updateProgress != null) return
        updateError = null; updateProgress = 0f
        scope.launch(Dispatchers.IO) {
            runCatching {
                val updates = AppHooks.updates ?: error("Updates are not available here")
                updateStage = "Downloading…"
                val file = updates.download(info) { updateProgress = it }
                AppHooks.log("Update ${info.version} downloaded from ${info.repo}; starting the installer.")
                val silent = updates.installsSilently(info)
                if (silent) updateStage = "Installing… W's MUSIC closes for a few seconds and reopens by itself."
                updates.launchInstaller(file)
                if (silent) delay(1800) // time to read it before the window closes
            }.onSuccess {
                updateProgress = null
                requestExit?.invoke()
            }.onFailure {
                AppHooks.log("Update ${info.version} failed: ${it.message?.take(160)}")
                updateProgress = null
                updateStage = null
                updateError = it.message?.take(140) ?: "The update could not be downloaded"
            }
        }
    }

    /** Warms up the stream resolver so the first song starts sooner. */
    fun warmUp() {
        scope.launch(Dispatchers.IO) {
            runCatching { AppHooks.updates?.lastUpdateResult() }.getOrNull()?.let { scope.launch { delay(1500); showToast(it) } }
            runCatching { if (YouTube.visitorData == null) YouTube.visitorData().onSuccess { YouTube.visitorData = it } }
            loadHome() // the feed no longer waits for the stream resolver warm-up
            delay(AppHooks.backgroundDelayMs) // keep the first seconds light; this only shortens the first song's start
            InnerTubeXStream.prewarm()
            refreshMixes()
        }
    }

    /** Saves the last listen and settings; called when the window closes. */
    fun shutdown() {
        sync.stop()
        runCatching { voiceMeter?.close() }
        val song = playingSong; val ms = playedMax
        val duration = playback.durationMs.takeIf { it > 0 } ?: ((song?.duration ?: 0) * 1000L)
        if (song != null) runBlocking {
            runCatching {
                if (ms >= 10_000) library.recordPlay(song, ms)
                if (ms > 0) library.recordSignal(song, ms, duration, playStartReason, EndReason.CLOSED, playContext)
            }
        }
        saveSettings()
    }

    private fun loadSettings() {
        val f = settingsFile?.takeIf { it.isFile } ?: return
        runCatching {
            val p = Properties().apply { f.inputStream().use(::load) }
            p.getProperty("volume")?.toFloatOrNull()?.let { volume = it.coerceIn(0f, 1f) }
            p.getProperty("autoplay")?.let { autoplay = it.toBoolean() }
            p.getProperty("voiceDuck")?.let { v -> voiceDuck = VoiceDuck.entries.firstOrNull { it.name == v } ?: VoiceDuck.OFF }
            p.getProperty("panelOpen")?.let { panelOpen = it.toBoolean() }
            p.getProperty("repeat")?.let { r -> RepeatMode.entries.firstOrNull { it.name == r }?.let { queue = queue.copy(repeat = it) } }
            p.getProperty("panelTab")?.let { t -> PanelTab.entries.firstOrNull { it.name == t }?.let { panelTab = it } }
            p.getProperty("sidebarWidth")?.toFloatOrNull()?.let { sidebarWidth = it.coerceIn(SIDEBAR_MIN, SIDEBAR_MAX) }
            p.getProperty("sidebarCollapsed")?.let { sidebarCollapsed = it.toBoolean() }
            p.getProperty("tastePromptDismissed")?.let { tastePromptDismissed = it.toBoolean() }
            p.stringPropertyNames().filter { it.startsWith("shuffle.") }.forEach { k ->
                ShuffleMode.entries.firstOrNull { it.name == p.getProperty(k) }?.let { shuffleModes[k.removePrefix("shuffle.")] = it }
            }
        }
    }

    private fun saveSettings() {
        val f = settingsFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            Properties().apply {
                setProperty("volume", volume.toString()); setProperty("autoplay", autoplay.toString())
                setProperty("voiceDuck", voiceDuck.name)
                setProperty("panelOpen", panelOpen.toString()); setProperty("repeat", queue.repeat.name)
                setProperty("panelTab", panelTab.name)
                setProperty("sidebarWidth", sidebarWidth.toString()); setProperty("sidebarCollapsed", sidebarCollapsed.toString())
                setProperty("tastePromptDismissed", tastePromptDismissed.toString())
                shuffleModes.entries.take(200).forEach { (ctx, mode) -> setProperty("shuffle.$ctx", mode.name) }
            }.let { p -> f.outputStream().use { p.store(it, "W's MUSIC settings") } }
        }
    }
}

/** Discord's sound above this (peak, 0..1) means someone is talking; silence there is exactly 0. */
private const val VOICE_THRESHOLD = 0.01f
/** Music volume while someone talks, in "lower" mode. */
private const val DUCKED_VOLUME = 0.2f
