package echopc

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.models.YouTubeLocale
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.music.innertube.YouTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

data class ResolvedStream(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val client: String,
    val headers: Map<String, String>,
    val sabr: SabrBootstrap? = null,
)

/**
 * Stream resolution through InnerTubeX, the same library the Android app uses
 * (see app/.../utils/InnerTubeXResolver.kt). The Android-only parts are replaced:
 * prefs -> in-memory config store, WebView PO tokens -> none.
 */
object InnerTubeXStream {
    private const val PLAYER_CONFIG_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"

    var verbose = false

    val httpClient get() = http
    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        expectSuccess = false
    }

    private val logger = InnerTubeLogger { event ->
        if (verbose || event.level != InnerTubeLogLevel.DEBUG) {
            println("[itx] ${event.tag}: ${event.message} ${event.details.takeIf { it.isNotEmpty() } ?: ""}")
        }
    }

    private val repository = object : PlayerConfigRepository {
        override val enabled: Boolean = true
        override val sourceUrl: String = PLAYER_CONFIG_URL
        override val defaultSourceUrl: String = PLAYER_CONFIG_URL
        override var cachedJson: String = ""
        override var cachedAtMs: Long = 0L
        override var cachedSourceUrl: String = ""
        override var cachedEtag: String = ""
    }

    private val tokenProvider = object : TokenProvider {
        override val capabilities = TokenProviderCapabilities(providers = emptySet(), usesWebView = false)
        override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? = null
        override suspend fun close() {}
    }

    private val innerTube = InnerTube(http, logger = logger)
    private val remoteStore = RemotePlayerConfigStore(http, repository, logger)
    private val cipherService = YouTubeCipherService(http, remoteStore, logger)
    private val extractor = InnerTubeExtractor(
        configParser = YtConfigParserImpl(http, innerTube, remoteStore, logger),
        cipherService = cipherService,
        innerTube = innerTube,
        tokenProvider = tokenProvider,
        logger = logger,
    )

    /** Loads the player/cipher configuration ahead of the first song so it starts sooner. */
    suspend fun prewarm() {
        runCatching {
            if (YouTube.visitorData == null) YouTube.visitorData().onSuccess { YouTube.visitorData = it }
            innerTube.cookie = YouTube.cookie
            innerTube.visitorData = YouTube.visitorData
            innerTube.locale = YouTubeLocale(gl = "US", hl = "en")
            extractor.prewarm()
        }
    }

    suspend fun resolve(videoId: String, requireM4a: Boolean = true, allowSabr: Boolean = true, skipClients: Set<String> = emptySet()): ResolvedStream {
        if (YouTube.visitorData == null) {
            YouTube.visitorData().onSuccess { YouTube.visitorData = it }
        }
        innerTube.cookie = YouTube.cookie
        innerTube.visitorData = YouTube.visitorData
        innerTube.locale = YouTubeLocale(gl = "US", hl = "en")
        val stream = extractor.extract(
            videoId = videoId,
            hints = ContentHints().withStreamCapabilities(allowHls = false, allowSabr = allowSabr, allowBoundedRange = true),
            excludedClients = skipClients,
            audioQuality = if (requireM4a) AudioQuality.MP4 else AudioQuality.AUTO,
            clientPlaybackNonce = generateClientPlaybackNonce(),
        ) ?: error("InnerTubeX found no stream for $videoId")
        if (stream.sabrBootstrap != null && !allowSabr) error("SABR-only stream (client ${stream.clientName})")
        return ResolvedStream(
            url = stream.audioUrl,
            mimeType = stream.mimeType.orEmpty() + (stream.codecs?.let { "; codecs=\"$it\"" } ?: ""),
            bitrate = stream.bitrate ?: 0,
            client = stream.clientName,
            headers = stream.headers,
            sabr = stream.sabrBootstrap,
        )
    }
}

/** Streams the SABR audio of [bootstrap] into [out]; returns total bytes written. */
@OptIn(ExperimentalSabrApi::class)
suspend fun downloadSabrAudio(bootstrap: SabrBootstrap, out: java.io.OutputStream, log: (String) -> Unit = {}): Long {
    val started = System.currentTimeMillis()
    val stream = SabrAudioStream(
        httpClient = InnerTubeXStream.httpClient,
        bootstrap = bootstrap,
        // Read-ahead is throttled by "playback position"; we download the whole track up front,
        // so report a position that advances faster than real time.
        playbackPositionMs = { (System.currentTimeMillis() - started) * 1000 },
    )
    var total = 0L
    stream.bytes().collect { chunk ->
        out.write(chunk)
        total += chunk.size
    }
    log("SABR download finished: $total bytes, mime=${bootstrap.mimeType}")
    return total
}

