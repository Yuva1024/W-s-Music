package echopc

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Where the loader reports stream problems; the desktop app points it at wsmusic.log. */
object StreamLog {
    @Volatile var sink: (String) -> Unit = {}
    fun log(message: String) = sink(message)
}

/**
 * Downloads a track's audio into a temp file. Results are cached per videoId so the next song
 * can be fetched while the current one plays.
 */
/** A download that failed after the stream was resolved; remembers which client served it so the retry can use another. */
class StreamFailure(val client: String?, cause: Throwable) : Exception(cause.message ?: cause.toString(), cause)

class AudioLoader(
    /** (videoId, clients to avoid, SABR allowed) -> stream. Replaced in tests. */
    private val resolver: suspend (String, Set<String>, Boolean) -> ResolvedStream =
        { id, skip, sabr -> InnerTubeXStream.resolve(id, requireM4a = true, allowSabr = sabr, skipClients = skip) },
    /** Writes the stream into the file. Replaced in tests. */
    private val downloadOverride: (suspend (ResolvedStream, Path) -> Unit)? = null,
    private val retryDelayMs: Long = 500L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Deferred<Path>>()

    /** Starts (or reuses) the download for [videoId]. */
    fun load(videoId: String): Deferred<Path> =
        cache.computeIfAbsent(videoId) {
            scope.async {
                var lastError: Throwable? = null
                val skip = mutableSetOf<String>()
                var protectedByYouTube = false // YouTube asked for attestation: every client fails the same way for a while
                var attempt = 0
                while (attempt < (if (protectedByYouTube) MAX_ATTEMPTS_BLOCKED else MAX_ATTEMPTS)) {
                    try {
                        // 1: normal. 2: avoid the client that just failed. 3-4: no SABR (plain ranged URLs), then any client again.
                        // After an attestation block none of that helps, so keep the normal way and wait instead.
                        if (attempt == 3) skip.clear()
                        val path = fetch(videoId, skip, allowSabr = protectedByYouTube || attempt < 2)
                        if (attempt > 0) StreamLog.log("Stream for $videoId recovered on attempt ${attempt + 1} (avoiding ${skip.ifEmpty { "nothing" }}).")
                        return@async path
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        lastError = e
                        val blocked = e.message?.contains("attestation", ignoreCase = true) == true
                        if (blocked) protectedByYouTube = true else (e as? StreamFailure)?.client?.let { skip += it }
                        val client = (e as? StreamFailure)?.client ?: "unknown client"
                        val limit = if (protectedByYouTube) MAX_ATTEMPTS_BLOCKED else MAX_ATTEMPTS
                        val msg = "Stream $videoId attempt ${attempt + 1}/$limit failed via $client: ${e.message?.take(200)}"
                        println("[loader] $msg")
                        StreamLog.log(msg)
                        // An attestation block clears by itself after some seconds: wait 3, 6, 10, 15, 20, 25 s between tries.
                        kotlinx.coroutines.delay(retryDelayMs * (if (blocked) BLOCK_WAITS[minOf(attempt, BLOCK_WAITS.lastIndex)] else (attempt + 1).toLong()))
                    }
                    attempt++
                }
                cache.remove(videoId)
                StreamLog.log("Stream $videoId gave up after $attempt attempts.")
                throw lastError!!
            }
        }

    /** One full attempt: resolve a fresh stream URL, then download it. */
    private suspend fun fetch(videoId: String, skip: Set<String>, allowSabr: Boolean): Path {
        val t0 = System.currentTimeMillis()
        val stream = resolver(videoId, skip, allowSabr)
        val t1 = System.currentTimeMillis()
        val file = Files.createTempFile("echopc-", if (stream.mimeType.contains("webm")) ".webm" else ".m4a")
        file.toFile().deleteOnExit()
        try {
            val override = downloadOverride
            if (override != null) {
                override(stream, file)
            } else if (stream.sabr != null) {
                val bytes = Files.newOutputStream(file).use { out -> downloadSabrAudio(stream.sabr, out) }
                if (bytes == 0L) error("SABR stream returned no data (via ${stream.client})")
            } else {
                downloadRanged(stream, file)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { Files.deleteIfExists(file) }
            throw e
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(file) }
            throw StreamFailure(stream.client, e)
        }
        println("[loader] $videoId via ${stream.client}: resolve ${t1 - t0} ms, download ${System.currentTimeMillis() - t1} ms")
        return file
    }

    /** Drops cached files except those in [keep]. */
    fun retainOnly(keep: Set<String>) {
        for (id in cache.keys.toList()) {
            if (id in keep) continue
            val d = cache.remove(id) ?: continue
            if (d.isCompleted && !d.isCancelled) runCatching { Files.deleteIfExists(d.getCompleted()) } else d.cancel()
        }
    }

    private suspend fun downloadRanged(stream: ResolvedStream, file: Path) {
        val chunk = 512 * 1024L
        var start = 0L
        var total = Long.MAX_VALUE
        Files.newOutputStream(file).use { out ->
            while (start < total) {
                val response = InnerTubeXStream.httpClient.get(stream.url) {
                    header("Range", "bytes=$start-${start + chunk - 1}")
                    stream.headers.forEach { (k, v) -> if (!k.equals("Range", true) && !k.equals("Host", true)) header(k, v) }
                }
                val code = response.status.value
                if (code == 416) break
                if (code !in 200..299) error("Stream download failed at byte $start: HTTP $code (via ${stream.client})")
                val body = response.readRawBytes()
                out.write(body)
                start += body.size
                val range = response.headers["Content-Range"]
                if (range != null) total = range.substringAfter('/').toLongOrNull() ?: total
                else if (code == 200) break
                if (body.isEmpty()) break
            }
        }
        if (start == 0L) error("Empty stream (via ${stream.client})")
    }
}

private const val MAX_ATTEMPTS = 4
private const val MAX_ATTEMPTS_BLOCKED = 7 // when YouTube is blocking streams, keep trying for about a minute
private val BLOCK_WAITS = longArrayOf(6, 12, 20, 30, 40, 50) // in units of the retry delay (500 ms)
