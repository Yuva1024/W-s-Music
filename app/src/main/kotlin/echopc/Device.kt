package echopc

import java.io.File
import java.util.Properties

/**
 * What kind of PC the app is running on, and the choices that follow from it.
 *
 * - **Lite** (2 or fewer CPU cores, or about 4 GB of RAM or less): smaller picture cache, slower
 *   background work and the light audio engine. Everything else behaves the same.
 * - **Safe rendering**: if the previous start never got a window on screen (a graphics driver without
 *   working OpenGL, for example), the next start draws with the CPU instead. The choice is remembered.
 * - **Compatible audio**: if the JavaFX audio engine cannot start or open a track (Windows "N"
 *   editions without the Media Feature Pack, missing runtimes), the pure-Java engine takes over.
 *
 * Settings live in `%APPDATA%\WsMusic\device.properties`; problems are logged to `wsmusic.log` next to it.
 * Environment overrides: WSMUSIC_LITE=1|0, WSMUSIC_RENDER=OPENGL|SOFTWARE_COMPAT|..., WSMUSIC_AUDIO=fx|compat.
 */
object Device {
    private val dir: File = settingsFile().parentFile.also { it.mkdirs() }
    private val file = File(dir, "device.properties")
    private val launchFlag = File(dir, "launching.flag")
    private val logFile = File(dir, "wsmusic.log")
    private val props = Properties().apply { runCatching { if (file.isFile) file.inputStream().use(::load) } }

    val cores: Int = Runtime.getRuntime().availableProcessors()
    val ramMb: Long = runCatching {
        (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize / (1024 * 1024)
    }.getOrDefault(0L)

    /** True on weak hardware (or when forced with WSMUSIC_LITE=1). */
    val lite: Boolean = when (System.getenv("WSMUSIC_LITE")) {
        "1" -> true
        "0" -> false
        else -> cores <= 2 || (ramMb in 1..4600)
    }

    /** Skiko render API in use ("OPENGL" normally, "SOFTWARE_COMPAT" after a failed start). */
    var renderApi: String = "OPENGL"
        private set

    /** True when the pure-Java audio engine is in use instead of JavaFX. */
    @Volatile var audioCompat: Boolean = false
        private set

    private fun save() = runCatching { file.outputStream().use { props.store(it, "W's MUSIC device settings") } }

    fun log(message: String) {
        if (System.getProperty("wsmusic.nolog") != null) return // tests do not write to the real log
        runCatching {
            if (logFile.length() > 512 * 1024) logFile.writeText("")
            logFile.appendText("${java.time.LocalDateTime.now()}  $message\n")
        }
    }

    /**
     * Picks the render API and audio engine and arms the "did the last start work" check.
     * Must run before any Compose/Skiko class is touched.
     */
    fun configure() {
        val crashedLastTime = launchFlag.exists()
        if (crashedLastTime && props.getProperty("render") == null) {
            props.setProperty("render", "SOFTWARE_COMPAT")
            save()
            log("Previous start did not reach a visible window; switching to software rendering.")
        }
        renderApi = System.getenv("WSMUSIC_RENDER") ?: props.getProperty("render") ?: "OPENGL"
        System.setProperty("skiko.renderApi", renderApi)
        audioCompat = when (System.getenv("WSMUSIC_AUDIO")) {
            "compat" -> true
            "fx" -> false
            else -> props.getProperty("audio")?.let { it == "compat" } ?: lite
        }
        runCatching { launchFlag.writeText(System.currentTimeMillis().toString()) }
        StreamLog.sink = ::log
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            log("Uncaught exception in ${t.name}: ${e.stackTraceToString().take(1500)}")
            System.err.println("Uncaught exception in ${t.name}")
            e.printStackTrace()
        }
        log("Start: lite=$lite cores=$cores ram=${ramMb}MB render=$renderApi audio=${if (audioCompat) "compat" else "javafx"} java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    }

    /** Called once the window has been up for a few seconds: the start counts as good. */
    fun launchSucceeded() {
        runCatching { launchFlag.delete() }
    }

    /** Remembers (or forgets) that JavaFX audio does not work on this PC. */
    fun useCompatAudio(reason: String) {
        if (audioCompat) return
        audioCompat = true
        props.setProperty("audio", "compat")
        save()
        log("JavaFX audio failed ($reason); using the compatible audio engine from now on.")
    }

    /** Memory budget for album art, in bytes. */
    val thumbBudget: Long get() = if (lite) 12L * 1024 * 1024 else 40L * 1024 * 1024

    /** Extra delay before background work (mix building) after startup. */
    val backgroundDelayMs: Long get() = if (lite) 12_000 else 4_000
}
