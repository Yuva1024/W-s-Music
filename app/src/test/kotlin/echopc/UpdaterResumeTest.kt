package echopc

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** The update download continues where a dropped connection stopped, and still checks the file. */
class UpdaterResumeTest {
    @Test
    fun aDroppedDownloadContinuesWhereItStopped() {
        val data = Random(7).nextBytes(3_000_000)
        val ranges = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/WsMusic-9.9.9.msi") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            synchronized(ranges) { ranges += range }
            if (range == null) {
                // First try: promise the whole file, send a third, then the connection drops.
                ex.sendResponseHeaders(200, data.size.toLong())
                ex.responseBody.write(data, 0, data.size / 3)
                ex.close()
            } else {
                val from = range.removePrefix("bytes=").substringBefore('-').toInt()
                ex.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
                ex.sendResponseHeaders(206, (data.size - from).toLong())
                ex.responseBody.use { it.write(data, from, data.size - from) }
            }
        }
        server.start()
        try {
            val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            val info = UpdateInfo(
                version = "9.9.9", tag = "v9.9.9", repo = "test", assetName = "WsMusic-9.9.9.msi",
                downloadUrl = "http://127.0.0.1:${server.address.port}/WsMusic-9.9.9.msi", sizeBytes = data.size.toLong(), sha256 = sha, notes = "",
            )
            val file = Updater.download(info) {}
            assertContentEquals(data, Files.readAllBytes(file))
            assertTrue(ranges.size >= 2 && ranges[1]!!.startsWith("bytes="), "asked to continue: $ranges")
        } finally {
            server.stop(0)
        }
    }
}
