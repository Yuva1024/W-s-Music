package echopc.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.innertube.models.SongItem
import echopc.AppHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a device is playing, as the other device sees it ("Playing on Redmi Note 8"). */
data class RemoteState(
    val deviceId: String,
    val name: String,
    val items: List<SongItem>,
    val index: Int,
    val repeat: String,
    val playing: Boolean,
    val loading: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val volume: Float,
    /** How long it has been since it last played (0 while playing). */
    val idleMs: Long,
    val context: String,
    /** When this arrived here (System.nanoTime), to move the position on while it plays. */
    val receivedAt: Long = System.nanoTime(),
) {
    val current: SongItem? get() = items.getOrNull(index)
    private val ageMs: Long get() = (System.nanoTime() - receivedAt) / 1_000_000
    /** Position now, counting the time since this state arrived. */
    fun positionNow(): Long = if (playing && durationMs > 0) (positionMs + ageMs).coerceAtMost(durationMs) else positionMs
    fun idleNow(): Long = if (playing) 0 else idleMs + ageMs

    fun toJson(): JsonObject = buildJsonObject {
        put("id", deviceId); put("name", name); put("index", index); put("repeat", repeat)
        put("playing", playing); put("loading", loading); put("pos", positionMs); put("dur", durationMs)
        put("vol", volume.toDouble()); put("idle", idleMs); put("context", context)
        put("items", buildJsonArray { items.forEach { add(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JsonObject?): RemoteState? {
            o ?: return null
            return RemoteState(
                deviceId = o.s("id") ?: return null, name = o.s("name").orEmpty(),
                items = (o["items"] as? JsonArray).orEmpty().mapNotNull { it.toSong() },
                index = o.l("index")?.toInt() ?: -1, repeat = o.s("repeat") ?: "OFF",
                playing = o.b("playing") == true, loading = o.b("loading") == true,
                positionMs = o.l("pos") ?: 0, durationMs = o.l("dur") ?: 0,
                volume = (o["vol"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0.8f,
                idleMs = o.l("idle") ?: Long.MAX_VALUE / 4, context = o.s("context").orEmpty(),
            )
        }
    }
}

/** The app's player, as remote control needs it (the Controller). */
interface RemotePlayer {
    /** What this device is playing now. */
    fun remoteSnapshot(): RemoteState
    /** Runs a command from the other device (play, pause, next, a new queue, ...). */
    fun runRemote(command: JsonObject)
}

/** Remote commands. */
object RemoteCommand {
    fun simple(type: String) = buildJsonObject { put("t", type) }
    fun seek(ms: Long) = buildJsonObject { put("t", "seek"); put("ms", ms) }
    fun volume(v: Float) = buildJsonObject { put("t", "volume"); put("v", v.toDouble()) }
    fun jump(index: Int) = buildJsonObject { put("t", "jump"); put("index", index) }
    fun song(type: String, song: SongItem) = buildJsonObject { put("t", type); put("song", song.toJson()) }

    /** Play [items] from [index] there (a song picked on this device, or a handoff with [positionMs]). */
    fun queue(items: List<SongItem>, index: Int, context: String, positionMs: Long = 0, playing: Boolean = true, repeat: String = "OFF") = buildJsonObject {
        put("t", "queue"); put("index", index); put("context", context); put("pos", positionMs); put("playing", playing); put("repeat", repeat)
        put("items", buildJsonArray { items.forEach { add(it.toJson()) } })
    }

    fun items(c: JsonObject): List<SongItem> = (c["items"] as? JsonArray).orEmpty().mapNotNull { it.toSong() }
    fun song(c: JsonObject): SongItem? = c["song"]?.toSong()
}

/**
 * Spotify-Connect-style remote control between the paired phone and computer. The phone keeps a long-poll open
 * to the computer: each request carries the phone's state and comes back as soon as the computer's state changes
 * or the computer has a command for the phone (or after 25 s). Commands from the phone go straight to the computer.
 * Everything is encrypted with the pairing key, like sync.
 */
class RemoteLink(private val sync: SyncManager, private val scope: CoroutineScope) {
    var player: RemotePlayer? = null

    /** The other device's playback, or null when it isn't connected. */
    var other by mutableStateOf<RemoteState?>(null); private set
    var connected by mutableStateOf(false); private set
    /** Moves on twice a second while the other device plays, so progress bars follow it. */
    var otherPositionMs by mutableLongStateOf(0L); private set

    private var lastHeard = 0L
    private var tickJob: Job? = null

    private fun received(state: RemoteState?) {
        lastHeard = System.nanoTime()
        connected = true
        if (state != null) { other = state; otherPositionMs = state.positionNow() }
        if (tickJob == null) tickJob = scope.launch {
            while (isActive) {
                delay(500)
                other?.let { otherPositionMs = it.positionNow() }
                if (connected && (System.nanoTime() - lastHeard) / 1_000_000 > 40_000) { connected = false } // missed its polls
            }
        }
    }

    // ================================================================= computer side

    /** Bumped whenever the computer's playback changes; a waiting poll returns then. */
    private val version = MutableStateFlow(0L)
    private val outbox = HashMap<String, MutableList<JsonObject>>()

    /** This device's playback changed (song, play/pause, seek, volume, queue). */
    fun localChanged() {
        if (sync.isPc) version.value = version.value + 1 else postStateSoon()
    }

    internal suspend fun handle(peer: SyncPeer, path: String, req: JsonObject): JsonObject = when (path) {
        "/remote/state" -> { received(RemoteState.fromJson(req["state"] as? JsonObject)); buildJsonObject { put("ok", true) } }
        "/remote/cmd" -> {
            received(null)
            (req["cmd"] as? JsonObject)?.let { cmd -> player?.runRemote(cmd) }
            buildJsonObject { put("ok", true) }
        }
        else -> { // /remote/poll
            received(RemoteState.fromJson(req["state"] as? JsonObject))
            val since = req.l("since") ?: -1L
            withTimeoutOrNull(25_000) {
                version.first { it != since || synchronized(outbox) { outbox[peer.id].orEmpty().isNotEmpty() } }
            }
            val cmds = synchronized(outbox) { outbox.remove(peer.id).orEmpty() }
            buildJsonObject {
                put("version", version.value)
                player?.remoteSnapshot()?.let { put("state", it.toJson()) }
                put("cmds", JsonArray(cmds))
            }
        }
    }

    // ================================================================= sending commands (either side)

    /** Sends [command] to the other device. */
    fun send(command: JsonObject) {
        if (sync.isPc) {
            val target = other?.deviceId ?: sync.peers.firstOrNull()?.id ?: return
            synchronized(outbox) { outbox.getOrPut(target) { mutableListOf() } += command }
            version.value = version.value + 1 // wakes the phone's waiting poll
        } else {
            val pc = sync.peers.firstOrNull { it.role == "pc" } ?: return
            scope.launch(Dispatchers.IO) {
                runCatching { sync.call(pc, "/remote/cmd", buildJsonObject { put("cmd", command) }) }
                    .onFailure { AppHooks.log("Remote: command failed: ${it.message}") }
            }
        }
    }

    // ================================================================= phone side

    private var loopJob: Job? = null
    private var stateJob: Job? = null
    private var lastVersion = -1L

    internal fun startLoop() {
        if (sync.isPc || loopJob != null) return
        loopJob = scope.launch(Dispatchers.IO) {
            var backoff = 3_000L
            while (isActive) {
                val pc = sync.peers.firstOrNull { it.role == "pc" }
                if (pc == null) { delay(5_000); continue }
                val ok = runCatching {
                    val reply = sync.call(pc, "/remote/poll", buildJsonObject {
                        player?.remoteSnapshot()?.let { put("state", it.toJson()) }
                        put("since", lastVersion)
                    })
                    lastVersion = reply.l("version") ?: lastVersion
                    received(RemoteState.fromJson(reply["state"] as? JsonObject))
                    (reply["cmds"] as? JsonArray).orEmpty().forEach { c -> (c as? JsonObject)?.let { player?.runRemote(it) } }
                }.isSuccess
                if (ok) backoff = 3_000L
                else {
                    if (connected || backoff == 3_000L) sync.syncSoon(200) // a sync looks for the computer again if its address changed
                    connected = false
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    internal fun stopLoop() {
        loopJob?.cancel(); loopJob = null
        connected = false; other = null
    }

    /** Tells the computer about the phone's new state right away (the poll in progress still carries the old one). */
    private fun postStateSoon() {
        if (!connected) return
        val pc = sync.peers.firstOrNull { it.role == "pc" } ?: return
        stateJob?.cancel()
        stateJob = scope.launch(Dispatchers.IO) {
            delay(250)
            val state = player?.remoteSnapshot() ?: return@launch
            runCatching { sync.call(pc, "/remote/state", buildJsonObject { put("state", state.toJson()) }) }
        }
    }
}
