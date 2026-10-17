package echopc.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import echopc.AppHooks
import echopc.db.LibraryRepository
import echopc.db.SyncOp
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A device this one is paired with. [role]: "pc" (the phone's view of the computer) or "phone". */
data class SyncPeer(val id: String, val name: String, val key: String, val role: String, val host: String = "", val port: Int = 0, val lastSync: Long = 0)

/** A computer running W's MUSIC found on the Wi-Fi. */
data class FoundPc(val id: String, val name: String, val host: String, val port: Int)

/** On the PC: a phone asking to pair, with the code both screens show. */
data class PairRequest(val phoneName: String, val code: String)

/** On the phone: pairing progress. */
sealed interface PhonePairing {
    val pc: FoundPc
    data class Connecting(override val pc: FoundPc) : PhonePairing
    data class Confirm(override val pc: FoundPc, val code: String) : PhonePairing
    data class WaitingForPc(override val pc: FoundPc, val code: String) : PhonePairing
    data class Failed(override val pc: FoundPc, val message: String) : PhonePairing
}

/** A running listener that knows its port. */
interface HasPort { val port: Int }

data class SyncResponse(val status: Int, val body: ByteArray, val type: String = "application/json")

object SyncPorts {
    const val DISCOVERY = 47810
    const val HTTP = 47811
    const val DISCOVER_MESSAGE = "WSMUSIC_DISCOVER"
}

private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

internal fun SyncOp.toWire(): JsonObject = buildJsonObject {
    put("id", id); put("o", origin); put("s", seq); put("c", category); put("k", key); put("t", type); put("at", at); put("d", data)
}

internal fun JsonElement.toOp(): SyncOp? {
    val id = s("id") ?: return null
    return SyncOp(id, s("o") ?: return null, l("s") ?: return null, s("c") ?: return null, s("k") ?: return null, s("t") ?: return null, l("at") ?: return null, s("d") ?: return null)
}

private fun haveToJson(have: Map<String, Map<String, Long>>) = buildJsonObject {
    have.forEach { (origin, cats) -> putJsonObject(origin) { cats.forEach { (c, n) -> put(c, n) } } }
}

private fun haveFromJson(e: JsonElement?): Map<String, Map<String, Long>> =
    (e as? JsonObject).orEmpty().mapValues { (_, v) -> (v as? JsonObject).orEmpty().mapNotNull { (c, _) -> v.l(c)?.let { c to it } }.toMap() }

/**
 * Wi-Fi sync between the user's own phone and computer, no account and no internet service involved.
 * The computer listens (only once a phone is paired or pairing is open); the phone finds it on the Wi-Fi,
 * pulls the computer's changes and pushes its own. [onChanged] gets the categories that received changes.
 */
class SyncManager(
    library: LibraryRepository,
    private val scope: CoroutineScope,
    /** The computer's listener (see [AppHooks.syncServer]); null on the phone. */
    private val serverFactory: ((SyncHost) -> AutoCloseable)? = AppHooks.syncServer,
    private val onChanged: (Set<String>) -> Unit = {},
) {
    val store = SyncStore(library)
    private val dao = library.syncDao

    /** The computer runs the server; the phone app has none and connects to it. */
    val isPc: Boolean get() = serverFactory != null

    var peers by mutableStateOf<List<SyncPeer>>(emptyList()); private set
    var syncLibrary by mutableStateOf(true); private set
    var syncTaste by mutableStateOf(true); private set
    var status by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set

    // computer
    var pairingOpen by mutableStateOf(false); private set
    var pairRequest by mutableStateOf<PairRequest?>(null); private set
    var serverError by mutableStateOf<String?>(null); private set
    var addresses by mutableStateOf<List<String>>(emptyList()); private set
    /** The port the computer listens on (shown with its address). */
    var port by mutableStateOf(SyncPorts.HTTP); private set

    // phone
    var found by mutableStateOf<List<FoundPc>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    var pairing by mutableStateOf<PhonePairing?>(null); private set

    private val host = SyncHost()
    private var server: AutoCloseable? = null
    private var watchJob: Job? = null
    private var loopJob: Job? = null
    private var soonJob: Job? = null
    private val syncLock = Mutex()
    @Volatile private var quietUntil = 0L

    private val http by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) { connectTimeoutMillis = 3000; requestTimeoutMillis = 90_000; socketTimeoutMillis = 60_000 }
        }
    }

    fun start() {
        scope.launch(Dispatchers.IO) {
            peers = loadPeers()
            syncLibrary = store.enabled(SyncCategory.LIBRARY)
            syncTaste = store.enabled(SyncCategory.TASTE)
            onPeersChanged()
        }
    }

    private suspend fun loadPeers(): List<SyncPeer> = runCatching {
        (syncJson.parseToJsonElement(store.kv("peers") ?: "[]") as JsonArray).mapNotNull { p ->
            SyncPeer(p.s("id") ?: return@mapNotNull null, p.s("name").orEmpty(), p.s("key") ?: return@mapNotNull null, p.s("role") ?: "phone",
                p.s("host").orEmpty(), p.l("port")?.toInt() ?: 0, p.l("lastSync") ?: 0)
        }
    }.getOrDefault(emptyList())

    private suspend fun savePeers(list: List<SyncPeer>) {
        peers = list
        store.putKv("peers", buildJsonArray {
            list.forEach { p -> add(buildJsonObject { put("id", p.id); put("name", p.name); put("key", p.key); put("role", p.role); put("host", p.host); put("port", p.port); put("lastSync", p.lastSync) }) }
        }.toString())
    }

    private suspend fun updatePeer(id: String, change: (SyncPeer) -> SyncPeer) = savePeers(peers.map { if (it.id == id) change(it) else it })

    /** Starts or stops listening, change tracking and the phone's regular sync to match the paired devices. */
    private fun onPeersChanged() {
        val paired = peers.isNotEmpty()
        if (isPc) updateServer()
        if (paired && watchJob == null) {
            watchJob = scope.launch(Dispatchers.IO) {
                // Record changes a moment after they happen, so the log has the right times; the phone then syncs soon.
                dao.syncWatch().collectLatest {
                    delay(2000)
                    runCatching { store.reconcile() }.onFailure { AppHooks.log("Sync: reconcile failed: ${it.message}") }
                    if (!isPc && System.currentTimeMillis() > quietUntil) syncSoon(15_000)
                }
            }
        } else if (!paired) {
            watchJob?.cancel(); watchJob = null
        }
        if (!isPc && paired && loopJob == null) {
            loopJob = scope.launch(Dispatchers.IO) {
                delay(4000)
                while (true) {
                    syncNow(quiet = true)
                    delay(5 * 60_000L)
                }
            }
        } else if (!paired) {
            loopJob?.cancel(); loopJob = null
        }
    }

    /** App closing: stop listening. */
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    fun setSwitch(category: String, on: Boolean) {
        if (category == SyncCategory.LIBRARY) syncLibrary = on else syncTaste = on
        scope.launch(Dispatchers.IO) { store.setEnabled(category, on); if (on) syncSoon(500) }
    }

    fun unpair(id: String) {
        scope.launch(Dispatchers.IO) {
            savePeers(peers.filter { it.id != id })
            onPeersChanged()
            status = null
        }
    }

    private fun myCategories(): List<String> = listOfNotNull(SyncCategory.LIBRARY.takeIf { syncLibrary }, SyncCategory.TASTE.takeIf { syncTaste })

    private fun timeText(millis: Long): String = millis.toLocal().format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun receivedChanges(changed: Set<String>) {
        if (changed.isEmpty()) return
        quietUntil = System.currentTimeMillis() + 5000 // our own writes, not new changes to send back
        onChanged(changed)
    }

    // =====================================================================================
    // Computer side
    // =====================================================================================

    private fun updateServer() {
        val factory = serverFactory ?: return
        val wanted = peers.isNotEmpty() || pairingOpen
        if (wanted && server == null) {
            server = runCatching { factory(host) }
                .onFailure { serverError = "Couldn't start sync on this computer: ${it.message}"; AppHooks.log("Sync: server failed: ${it.message}") }
                .getOrNull()
            if (server != null) serverError = null
        } else if (!wanted && server != null) {
            runCatching { server?.close() }
            server = null
        }
        addresses = if (server != null) localAddresses() else emptyList()
        port = (server as? HasPort)?.port ?: SyncPorts.HTTP
    }

    /** Lets a phone pair for the next 3 minutes. */
    fun openPairing() {
        pairingOpen = true
        pairRequest = null
        updateServer()
        scope.launch {
            delay(3 * 60_000L)
            if (pairingOpen && pairRequest == null) closePairing()
        }
    }

    fun closePairing() {
        pairingOpen = false
        pairRequest = null
        session = null
        updateServer()
    }

    fun allowPair() {
        val s = session ?: return
        val phoneNonce = s.phoneNonce ?: return
        scope.launch(Dispatchers.IO) {
            val key = SyncCrypto.sessionKey(SyncCrypto.agree(s.keys.private, s.phonePublic), phoneNonce, s.pcNonce)
            savePeers(peers.filter { it.id != s.phoneId } + SyncPeer(s.phoneId, s.phoneName, SyncCrypto.b64(key), "phone"))
            s.state = "allowed"
            pairRequest = null
            pairingOpen = false
            status = "Paired with ${s.phoneName}. It syncs whenever W's MUSIC is open on both."
            onPeersChanged()
            store.reconcile() // the first sync sends everything
        }
    }

    fun denyPair() {
        session?.state = "denied"
        pairRequest = null
    }

    private class PcSession(val phoneId: String, val phoneName: String, val phonePublic: ByteArray, val keys: KeyPair, val pcNonce: ByteArray) {
        var phoneNonce: ByteArray? = null
        @Volatile var state = "waiting"
    }

    @Volatile private var session: PcSession? = null

    /** Handles the phone's requests; the platform's HTTP server calls it. */
    inner class SyncHost {
        fun discoveryReply(port: Int): String = runBlockingName().let { (id, name) ->
            buildJsonObject { put("app", "wsmusic"); put("id", id); put("name", name); put("port", port) }.toString()
        }

        private fun runBlockingName(): Pair<String, String> = kotlinx.coroutines.runBlocking { store.deviceId() to AppHooks.deviceName() }

        suspend fun handle(path: String, device: String?, body: ByteArray): SyncResponse = runCatching {
            when (path) {
                "/pair/hello" -> pairHello(syncJson.parseToJsonElement(body.decodeToString()))
                "/pair/nonce" -> pairNonce(syncJson.parseToJsonElement(body.decodeToString()))
                "/pair/status" -> json(buildJsonObject {
                    val id = syncJson.parseToJsonElement(body.decodeToString()).s("id")
                    put("state", session?.takeIf { it.phoneId == id }?.state ?: "gone")
                })
                "/pair/cancel" -> {
                    val id = syncJson.parseToJsonElement(body.decodeToString()).s("id")
                    if (session?.phoneId == id) { session = null; pairRequest = null }
                    json(buildJsonObject { put("ok", true) })
                }
                "/sync/pull", "/sync/push" -> sealed(path, device, body)
                else -> SyncResponse(404, "{}".toByteArray())
            }
        }.getOrElse { e ->
            AppHooks.log("Sync: ${path} failed: ${e.message}")
            SyncResponse(500, buildJsonObject { put("error", e.message ?: "error") }.toString().toByteArray())
        }

        private fun json(o: JsonObject, status: Int = 200) = SyncResponse(status, o.toString().toByteArray())

        private suspend fun pairHello(req: JsonElement): SyncResponse {
            if (!pairingOpen) return json(buildJsonObject { put("error", "On the computer, open Sync and click \"Pair a phone\" first.") }, 403)
            val id = req.s("id") ?: return json(buildJsonObject { put("error", "bad request") }, 400)
            val keys = SyncCrypto.newKeyPair()
            val s = PcSession(id, req.s("name").orEmpty().ifBlank { "Phone" }, SyncCrypto.unb64(req.s("pub").orEmpty()), keys, SyncCrypto.nonce())
            session = s
            val pcPublic = keys.public.encoded
            return json(buildJsonObject {
                put("id", store.deviceId()); put("name", AppHooks.deviceName()); put("pub", SyncCrypto.b64(pcPublic))
                put("commit", SyncCrypto.b64(SyncCrypto.commitment(pcPublic, s.phonePublic, s.pcNonce)))
            })
        }

        private fun pairNonce(req: JsonElement): SyncResponse {
            val s = session?.takeIf { it.phoneId == req.s("id") && it.phoneNonce == null } ?: return json(buildJsonObject { put("error", "Start again on the phone.") }, 409)
            val phoneNonce = SyncCrypto.unb64(req.s("nonce").orEmpty())
            s.phoneNonce = phoneNonce
            pairRequest = PairRequest(s.phoneName, SyncCrypto.code(s.phonePublic, s.keys.public.encoded, phoneNonce, s.pcNonce))
            return json(buildJsonObject { put("nonce", SyncCrypto.b64(s.pcNonce)) })
        }

        private suspend fun sealed(path: String, device: String?, body: ByteArray): SyncResponse {
            val peer = peers.firstOrNull { it.id == device } ?: return SyncResponse(401, "{\"error\":\"not paired\"}".toByteArray())
            val key = SyncCrypto.unb64(peer.key)
            val aad = (path + device).toByteArray()
            val req = syncJson.parseToJsonElement(gunzip(SyncCrypto.open(key, body, aad)).decodeToString()).jsonObject
            val reply = when (path) {
                "/sync/pull" -> {
                    val allowed = (req["cats"] as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.filter { it in myCategories() }
                    store.reconcile()
                    val ops = store.opsFor(haveFromJson(req["have"]), allowed)
                    buildJsonObject {
                        put("ops", buildJsonArray { ops.forEach { add(it.toWire()) } })
                        put("more", ops.size >= 2000)
                        put("have", haveToJson(store.have()))
                        put("cats", buildJsonArray { allowed.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                        put("name", AppHooks.deviceName())
                    }
                }
                else -> {
                    val allowed = myCategories()
                    val ops = (req["ops"] as? JsonArray).orEmpty().mapNotNull { it.toOp() }.filter { it.category in allowed }
                    receivedChanges(store.apply(ops))
                    buildJsonObject { put("ok", true); put("applied", ops.size) }
                }
            }
            if (path == "/sync/push" || req.b("last") == true) {
                updatePeer(peer.id) { it.copy(lastSync = LocalDateTime.now().millis(), name = req.s("name") ?: it.name) }
                status = "Synced with ${peer.name} at ${timeText(LocalDateTime.now().millis())}"
            }
            return SyncResponse(200, SyncCrypto.seal(key, gzip(reply.toString().toByteArray()), aad), "application/octet-stream")
        }
    }

    // =====================================================================================
    // Phone side
    // =====================================================================================

    /** Looks for computers running W's MUSIC on this Wi-Fi. */
    fun search() {
        if (searching) return
        searching = true
        scope.launch(Dispatchers.IO) {
            found = runCatching { discover() }.getOrDefault(emptyList())
            searching = false
        }
    }

    /** Starts pairing with [pc]; then the phone shows the code to compare with the computer's. */
    fun pairWith(pc: FoundPc) {
        pairing = PhonePairing.Connecting(pc)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val me = store.deviceId()
                val keys = SyncCrypto.newKeyPair()
                val myPublic = keys.public.encoded
                val hello = postJson(pc, "/pair/hello", buildJsonObject { put("id", me); put("name", AppHooks.deviceName()); put("pub", SyncCrypto.b64(myPublic)) })
                val pcPublic = SyncCrypto.unb64(hello.s("pub") ?: error("The computer didn't answer"))
                val commit = SyncCrypto.unb64(hello.s("commit").orEmpty())
                val myNonce = SyncCrypto.nonce()
                val reply = postJson(pc, "/pair/nonce", buildJsonObject { put("id", me); put("nonce", SyncCrypto.b64(myNonce)) })
                val pcNonce = SyncCrypto.unb64(reply.s("nonce") ?: error("The computer didn't answer"))
                if (!SyncCrypto.commitment(pcPublic, myPublic, pcNonce).contentEquals(commit)) error("Something on the network changed the pairing messages. Try again on your home Wi-Fi.")
                val key = SyncCrypto.sessionKey(SyncCrypto.agree(keys.private, pcPublic), myNonce, pcNonce)
                val pcFull = pc.copy(id = hello.s("id").orEmpty(), name = hello.s("name").orEmpty().ifBlank { pc.name })
                pendingKey = key
                pairing = PhonePairing.Confirm(pcFull, SyncCrypto.code(myPublic, pcPublic, myNonce, pcNonce))
            }.onFailure { e -> pairing = PhonePairing.Failed(pc, e.message ?: "Couldn't reach the computer") }
        }
    }

    private var pendingKey: ByteArray? = null

    /** The person checked that both screens show the same code; now waits for "Allow" on the computer. */
    fun confirmCode() {
        val p = pairing as? PhonePairing.Confirm ?: return
        val key = pendingKey ?: return
        pairing = PhonePairing.WaitingForPc(p.pc, p.code)
        scope.launch(Dispatchers.IO) {
            val me = store.deviceId()
            repeat(180) {
                if (pairing !is PhonePairing.WaitingForPc) return@launch
                val state = runCatching { postJson(p.pc, "/pair/status", buildJsonObject { put("id", me) }).s("state") }.getOrNull()
                when (state) {
                    "allowed" -> {
                        savePeers(peers.filter { it.id != p.pc.id } + SyncPeer(p.pc.id, p.pc.name, SyncCrypto.b64(key), "pc", p.pc.host, p.pc.port))
                        pairing = null; pendingKey = null
                        onPeersChanged()
                        syncNow()
                        return@launch
                    }
                    "denied", "gone" -> { pairing = PhonePairing.Failed(p.pc, "The computer didn't allow it. Try again."); return@launch }
                }
                delay(1000)
            }
            pairing = PhonePairing.Failed(p.pc, "Nobody clicked Allow on the computer in time. Try again.")
        }
    }

    fun cancelPairing() {
        val p = pairing
        pairing = null; pendingKey = null
        if (p != null) scope.launch(Dispatchers.IO) { runCatching { postJson(p.pc, "/pair/cancel", buildJsonObject { put("id", store.deviceId()) }) } }
    }

    fun syncSoon(delayMs: Long = 1000) {
        if (isPc || peers.isEmpty()) return
        soonJob?.cancel()
        soonJob = scope.launch(Dispatchers.IO) { delay(delayMs); syncNow(quiet = true) }
    }

    /** Swaps changes with every paired computer (skipped if a sync is already running). */
    fun syncNow(quiet: Boolean = false) {
        if (isPc) return
        scope.launch(Dispatchers.IO) {
            if (!syncLock.tryLock()) return@launch
            try { syncAll(quiet) } finally { syncLock.unlock() }
        }
    }

    /** Syncs now and returns when done (waits for a running sync first). */
    suspend fun syncOnce() = syncLock.withLock { syncAll(quiet = false) }

    private suspend fun syncAll(quiet: Boolean) {
        try {
            busy = !quiet || busy
            for (pc in peers.filter { it.role == "pc" }) syncWith(pc, quiet)
        } finally {
            busy = false
        }
    }

    private suspend fun syncWith(peer: SyncPeer, quiet: Boolean) {
        val cats = myCategories()
        if (cats.isEmpty()) { status = "Sync is off. Turn on Sync library or Sync music taste."; return }
        if (!quiet) status = "Syncing with ${peer.name}…"
        val result = runCatching {
            store.reconcile()
            var target = FoundPc(peer.id, peer.name, peer.host, peer.port)
            val first = runCatching { pull(target, peer, cats) }
            val (received, theirHave, allowed) = first.getOrElse {
                // Its address may have changed (new IP from the router): look for it again.
                val pc = discover().firstOrNull { it.id == peer.id } ?: throw it
                target = pc
                updatePeer(peer.id) { p -> p.copy(host = pc.host, port = pc.port) }
                pull(target, peer, cats)
            }
            val sent = push(target, peer, theirHave, allowed)
            updatePeer(peer.id) { it.copy(lastSync = LocalDateTime.now().millis()) }
            received to sent
        }
        status = result.fold(
            { (received, sent) ->
                val parts = listOfNotNull("$received received".takeIf { received > 0 }, "$sent sent".takeIf { sent > 0 })
                "Synced with ${peer.name} at ${timeText(LocalDateTime.now().millis())}" + if (parts.isEmpty()) "" else " (${parts.joinToString()})"
            },
            { e ->
                AppHooks.log("Sync: ${e.message}")
                if (e.message?.contains("401") == true) "${peer.name} doesn't know this phone any more. Unpair and pair again."
                else "Can't reach ${peer.name}. Make sure both are on the same Wi-Fi and W's MUSIC is open on the computer."
            },
        )
    }

    /** Gets the computer's changes. Returns (changes applied, what the computer has, categories both sync). */
    private suspend fun pull(pc: FoundPc, peer: SyncPeer, cats: List<String>): Triple<Int, Map<String, Map<String, Long>>, List<String>> {
        var received = 0
        while (true) {
            val reply = sealedPost(pc, peer, "/sync/pull", buildJsonObject {
                put("have", haveToJson(store.have()))
                put("cats", buildJsonArray { cats.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                put("name", AppHooks.deviceName())
            })
            val ops = (reply["ops"] as? JsonArray).orEmpty().mapNotNull { it.toOp() }
            receivedChanges(store.apply(ops))
            received += ops.size
            if (reply.b("more") != true || ops.isEmpty()) {
                val allowed = (reply["cats"] as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                reply.s("name")?.takeIf { it.isNotBlank() && it != peer.name }?.let { n -> updatePeer(peer.id) { it.copy(name = n) } }
                return Triple(received, haveFromJson(reply["have"]), allowed)
            }
        }
    }

    /** Sends the changes the computer lacks. */
    private suspend fun push(pc: FoundPc, peer: SyncPeer, theirHave: Map<String, Map<String, Long>>, cats: List<String>): Int {
        val have = theirHave.mapValues { it.value.toMutableMap() }.toMutableMap()
        var sent = 0
        while (true) {
            val ops = store.opsFor(have, cats)
            if (ops.isEmpty()) break
            sealedPost(pc, peer, "/sync/push", buildJsonObject { put("ops", buildJsonArray { ops.forEach { add(it.toWire()) } }); put("name", AppHooks.deviceName()) })
            ops.forEach { op -> val m = have.getOrPut(op.origin) { mutableMapOf() }; m[op.category] = maxOf(m[op.category] ?: 0, op.seq) }
            sent += ops.size
        }
        if (sent == 0) sealedPost(pc, peer, "/sync/push", buildJsonObject { put("ops", JsonArray(emptyList())); put("name", AppHooks.deviceName()) }) // marks the sync time on the computer
        return sent
    }

    private suspend fun sealedPost(pc: FoundPc, peer: SyncPeer, path: String, body: JsonObject): JsonObject {
        val key = SyncCrypto.unb64(peer.key)
        val me = store.deviceId()
        val aad = (path + me).toByteArray()
        val response = http.post("http://${pc.host}:${pc.port}$path") {
            header("X-Ws-Device", me)
            setBody(SyncCrypto.seal(key, gzip(body.toString().toByteArray()), aad))
        }
        if (response.status.value != 200) error("HTTP ${response.status.value}")
        return syncJson.parseToJsonElement(gunzip(SyncCrypto.open(key, response.readRawBytes(), aad)).decodeToString()).jsonObject
    }

    private suspend fun postJson(pc: FoundPc, path: String, body: JsonObject): JsonObject {
        val response = http.post("http://${pc.host}:${pc.port}$path") { setBody(body.toString()) }
        val reply = runCatching { syncJson.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        if (response.status.value != 200) error(reply.s("error") ?: "The computer answered with error ${response.status.value}")
        return reply
    }

    /** Broadcasts on the Wi-Fi; every W's MUSIC computer answers with its name and port. */
    suspend fun discover(): List<FoundPc> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, FoundPc>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 300
            val message = SyncPorts.DISCOVER_MESSAGE.toByteArray()
            val targets = (broadcastAddresses() + InetAddress.getByName("255.255.255.255")).distinct()
            repeat(3) {
                targets.forEach { runCatching { socket.send(DatagramPacket(message, message.size, it, SyncPorts.DISCOVERY)) } }
                val until = System.currentTimeMillis() + 700
                while (System.currentTimeMillis() < until) {
                    val buf = ByteArray(1024)
                    val packet = DatagramPacket(buf, buf.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val o = runCatching { syncJson.parseToJsonElement(String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
                    if (o.s("app") != "wsmusic") continue
                    val id = o.s("id") ?: continue
                    result[id] = FoundPc(id, o.s("name").orEmpty().ifBlank { "Computer" }, packet.address.hostAddress ?: continue, o.l("port")?.toInt() ?: SyncPorts.HTTP)
                }
                if (result.isNotEmpty()) return@use
            }
        }
        result.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())

    /** This computer's Wi-Fi/LAN addresses, to type on the phone when searching doesn't find it. */
    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    /** A computer typed in by address ("192.168.1.20" or "192.168.1.20:47811"). */
    fun manualPc(text: String): FoundPc? {
        val t = text.trim().removePrefix("http://").trimEnd('/')
        if (t.isEmpty()) return null
        val host = t.substringBefore(':')
        val port = t.substringAfter(':', "").toIntOrNull() ?: SyncPorts.HTTP
        return FoundPc("", host, host, port)
    }
}
