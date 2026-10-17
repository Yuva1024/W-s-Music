package echopc

import com.sun.net.httpserver.HttpServer
import echopc.sync.SyncManager
import echopc.sync.SyncPorts
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/**
 * The computer's side of Wi-Fi sync: a small HTTP server for the phone's (encrypted) requests and a UDP
 * responder so the phone can find this computer. Runs only while a phone is paired or pairing is open.
 * Windows may ask once whether W's MUSIC may use private networks: it needs "Allow".
 */
class DesktopSyncServer(private val host: SyncManager.SyncHost) : AutoCloseable, echopc.sync.HasPort {
    private val server: HttpServer
    private val udp: DatagramSocket?
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "sync-http").apply { isDaemon = true } }
    override val port: Int

    init {
        server = (SyncPorts.HTTP..SyncPorts.HTTP + 9).firstNotNullOfOrNull { p -> runCatching { HttpServer.create(InetSocketAddress(p), 8) }.getOrNull() }
            ?: error("ports ${SyncPorts.HTTP}-${SyncPorts.HTTP + 9} are all in use")
        port = server.address.port
        server.executor = pool
        server.createContext("/") { ex ->
            val response = runCatching {
                if (ex.requestMethod != "POST") echopc.sync.SyncResponse(405, ByteArray(0))
                else {
                    val body = ex.requestBody.use { it.readNBytes(64 * 1024 * 1024) }
                    runBlocking { host.handle(ex.requestURI.path, ex.requestHeaders.getFirst("X-Ws-Device"), body) }
                }
            }.getOrElse { echopc.sync.SyncResponse(500, ByteArray(0)) }
            ex.responseHeaders.add("Content-Type", response.type)
            ex.sendResponseHeaders(response.status, if (response.body.isEmpty()) -1 else response.body.size.toLong())
            if (response.body.isNotEmpty()) ex.responseBody.use { it.write(response.body) } else ex.close()
        }
        server.start()
        udp = runCatching { DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(SyncPorts.DISCOVERY)) } }
            .onFailure { Device.log("Sync: discovery port busy (${it.message}); the phone can still connect by address") }.getOrNull()
        val reply by lazy { host.discoveryReply(port).toByteArray() }
        udp?.let { socket ->
            thread(isDaemon = true, name = "sync-discovery") {
                val buf = ByteArray(256)
                while (!socket.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    runCatching { socket.receive(packet) }.getOrNull() ?: continue
                    if (String(packet.data, 0, packet.length) != SyncPorts.DISCOVER_MESSAGE) continue
                    runCatching { socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) }
                }
            }
        }
        Device.log("Sync: listening on port $port")
    }

    override fun close() {
        runCatching { udp?.close() }
        runCatching { server.stop(0) }
        pool.shutdownNow()
    }
}

/** "Kiran's PC": the Windows computer name. */
fun desktopName(): String =
    System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }?.let { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
        ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "Computer"
