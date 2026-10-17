package echopc

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull


/**
 * Self-update: looks for a newer release in the repositories below (the newest wins, so updates can be
 * published from either one), downloads its installer, checks it and starts it. The app then closes so the
 * installer can replace it; the library and settings live in %APPDATA% and are kept.
 */
object Updater : UpdateService {
    /** Where releases are published. The first one wins when both have the same version. */
    val repos = listOf("Yuva1024/W-s-Music", "Shriharish111/W-s-Music")

    /** The version this build was made as (written into version.properties by the Gradle build). */
    override fun currentVersion(): String = runCatching {
        Updater::class.java.getResourceAsStream("/version.properties")?.use { Properties().apply { load(it) }.getProperty("version") }
    }.getOrNull()?.takeIf { it.isNotBlank() && !it.contains('$') } ?: "0.0.0"

    /** "v1.2.3", "1.2.3-lowend" ... -> [1, 2, 3]. */
    fun parseVersion(text: String): List<Int>? =
        Regex("""(\d+)\.(\d+)\.(\d+)""").find(text)?.groupValues?.drop(1)?.map { it.toInt() }

    fun isNewer(candidate: List<Int>, current: List<Int>): Boolean {
        for (i in 0 until maxOf(candidate.size, current.size)) {
            val a = candidate.getOrElse(i) { 0 }; val b = current.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * Picks the update to offer from the JSON of each repository's "latest release" ([responses]: repo -> body).
     * Drafts and pre-releases are ignored, the download must be an .exe hosted by that same repository's releases, and
     * only a version higher than [current] counts.
     */
    fun choose(responses: Map<String, String>, current: String): UpdateInfo? {
        val now = parseVersion(current) ?: return null
        var best: UpdateInfo? = null
        var bestVersion: List<Int>? = null
        for (repo in repos) {
            val body = responses[repo] ?: continue
            val info = runCatching { parseRelease(repo, body) }.getOrNull() ?: continue
            val v = parseVersion(info.tag) ?: continue
            if (!isNewer(v, now)) continue
            if (bestVersion == null || isNewer(v, bestVersion)) { best = info; bestVersion = v }
        }
        return best
    }

    private fun parseRelease(repo: String, body: String): UpdateInfo? {
        val release = Json.parseToJsonElement(body).jsonObject
        if (release["draft"]?.jsonPrimitive?.booleanOrNull == true || release["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val assets = release["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        fun name(a: kotlinx.serialization.json.JsonObject) = a["name"]?.jsonPrimitive?.contentOrNull ?: ""
        // The .msi installs silently (no window, no questions) and the app reopens; the setup .exe is the fallback.
        val msi = assets.firstOrNull { name(it).endsWith(".msi", ignoreCase = true) }
        val exes = assets.filter { name(it).endsWith(".exe", ignoreCase = true) }
        val asset = msi ?: exes.firstOrNull { name(it).contains("setup", ignoreCase = true) } ?: exes.firstOrNull() ?: return null
        val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!url.startsWith("https://github.com/$repo/releases/download/")) return null // only this repository's own release files
        val digest = asset["digest"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:")?.lowercase()?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
        return UpdateInfo(
            version = parseVersion(tag)!!.joinToString("."), tag = tag, repo = repo,
            assetName = asset["name"]!!.jsonPrimitive.content, downloadUrl = url,
            sizeBytes = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L, sha256 = digest,
            notes = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
        )
    }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
    }

    /** Asks GitHub for the latest release of each repository; null if there is nothing newer (or no network). */
    override fun check(): UpdateInfo? = check(currentVersion())

    fun check(current: String): UpdateInfo? {
        val responses = HashMap<String, String>()
        for (repo in repos) {
            runCatching {
                val request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/$repo/releases/latest"))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "WsMusic-updater").timeout(Duration.ofSeconds(15)).build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) responses[repo] = response.body()
            }.onFailure { Device.log("Update check for $repo failed: ${it.message?.take(120)}") }
        }
        return choose(responses, current)
    }

    fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Downloads the installer to a temp folder and verifies its size and checksum; [onProgress] gets 0..1. */
    override fun download(info: UpdateInfo, onProgress: (Float) -> Unit): Path {
        val dir = Files.createTempDirectory("WsMusic-update-")
        val file = dir.resolve(info.assetName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        try {
            val request = HttpRequest.newBuilder(URI.create(info.downloadUrl)).header("User-Agent", "WsMusic-updater").build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            check(response.statusCode() == 200) { "Download failed: HTTP ${response.statusCode()}" }
            val total = response.headers().firstValueAsLong("Content-Length").orElse(info.sizeBytes).takeIf { it > 0 } ?: info.sizeBytes
            var done = 0L
            response.body().use { input ->
                Files.newOutputStream(file).use { out ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        out.write(buffer, 0, n); done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (info.sizeBytes > 0) check(Files.size(file) == info.sizeBytes) { "The download is incomplete" }
            info.sha256?.let { expected -> check(sha256Hex(file) == expected) { "The download does not match its checksum, so it was discarded" } }
            return file
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(file) }
            throw e
        }
    }

    /** Starts the downloaded installer. An .msi is installed silently by a hidden helper that then reopens the app. */
    override fun launchInstaller(file: Path) {
        val exe = appExe()
        if (!file.toString().endsWith(".msi", ignoreCase = true) || exe == null) {
            ProcessBuilder(if (file.toString().endsWith(".msi", true)) listOf("msiexec", "/i", file.toString()) else listOf(file.toString())).start()
            return
        }
        val dir = file.parent
        val log = dir.resolve("install.log")
        val script = dir.resolve("update.ps1")
        fun q(p: Any) = "'" + p.toString().replace("'", "''") + "'"
        Files.writeString(script, """
            |${'$'}ErrorActionPreference = 'SilentlyContinue'
            |Wait-Process -Id ${appPids().joinToString(",")} -Timeout 90
            |Start-Sleep -Milliseconds 700
            |${'$'}p = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', ${q("\"$file\"")}, '/qn', '/norestart', '/l*v', ${q("\"$log\"")}) -Wait -PassThru
            |Set-Content -Path ${q(dir.resolve("result.txt"))} -Value ${'$'}p.ExitCode
            |Start-Process -FilePath ${q(exe)}
            |""".trimMargin())
        pendingFile().toFile().writeText("${file.fileName}|$dir")
        ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString())
            .redirectErrorStream(true).redirectOutput(dir.resolve("helper.log").toFile()).start()
    }

    override fun installsSilently(info: UpdateInfo): Boolean = info.assetName.endsWith(".msi", ignoreCase = true) && appExe() != null

    /** This process and its WsMusic.exe launcher parent: the installer can only replace the files once both have exited. */
    private fun appPids(): List<Long> {
        val me = ProcessHandle.current()
        val parent = me.parent().orElse(null)?.takeIf { p -> p.info().command().orElse("").endsWith("WsMusic.exe", ignoreCase = true) }
        return listOfNotNull(me.pid(), parent?.pid())
    }

    /** The installed WsMusic.exe that is running now; null when started another way (from the build, tests). */
    private fun appExe(): Path? = ProcessHandle.current().info().command().orElse(null)
        ?.let { Path.of(it) }?.takeIf { it.fileName.toString().equals("WsMusic.exe", ignoreCase = true) && Files.isRegularFile(it) }

    private fun pendingFile(): Path = Path.of(System.getenv("APPDATA") ?: System.getProperty("user.home"), "WsMusic", "update-pending.txt")

    /**
     * After a silent update the app starts again: says whether it worked (and cleans up the download). Null when
     * no update was pending.
     */
    override fun lastUpdateResult(): String? {
        val pending = pendingFile()
        if (!Files.exists(pending)) return null
        val dir = runCatching { Path.of(Files.readString(pending).substringAfter('|').trim()) }.getOrNull()
        runCatching { Files.delete(pending) }
        val code = dir?.let { runCatching { Files.readString(it.resolve("result.txt")).trim().toInt() }.getOrNull() }
        if (code == 0 || code == 3010) { runCatching { dir.toFile().deleteRecursively() }; return "W's MUSIC was updated to ${currentVersion()}" }
        Device.log("Silent update failed: msiexec exit code $code, log in $dir")
        return "The update didn't install (code ${code ?: "unknown"}). You can try again from the update button."
    }
}
