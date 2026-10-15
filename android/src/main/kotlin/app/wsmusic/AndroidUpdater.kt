package app.wsmusic

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import echopc.InnerTubeXStream
import echopc.UpdateInfo
import echopc.UpdateService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Phone self-update: looks through the releases of both repositories for the newest version that has an .apk,
 * downloads it, checks its size and the SHA-256 GitHub publishes, and opens Android's installer.
 * (Android always asks the person to confirm an install for apps from outside the Play Store.)
 */
class AndroidUpdater(private val context: Context) : UpdateService {
    private val repos = listOf("Yuva1024/W-s-Music", "Shriharish111/W-s-Music")

    override fun currentVersion(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.0.0"

    private fun parseVersion(text: String): List<Int>? =
        Regex("""(\d+)\.(\d+)\.(\d+)""").find(text)?.groupValues?.drop(1)?.map { it.toInt() }

    private fun newer(a: List<Int>, b: List<Int>): Boolean {
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    override fun check(): UpdateInfo? = runBlocking {
        val now = parseVersion(currentVersion()) ?: return@runBlocking null
        var best: UpdateInfo? = null
        var bestVersion: List<Int>? = null
        for (repo in repos) {
            val body = runCatching {
                InnerTubeXStream.httpClient.get("https://api.github.com/repos/$repo/releases?per_page=20") {
                    header("Accept", "application/vnd.github+json"); header("User-Agent", "WsMusic-android-updater")
                }.bodyAsText()
            }.getOrNull() ?: continue
            val releases = runCatching { Json.parseToJsonElement(body).jsonArray }.getOrNull() ?: continue
            for (r in releases.map { it.jsonObject }) {
                if (r["draft"]?.jsonPrimitive?.booleanOrNull == true || r["prerelease"]?.jsonPrimitive?.booleanOrNull == true) continue
                val tag = r["tag_name"]?.jsonPrimitive?.contentOrNull ?: continue
                val v = parseVersion(tag) ?: continue
                if (!newer(v, now) || (bestVersion != null && !newer(v, bestVersion!!))) continue
                val apk = r["assets"]?.jsonArray?.map { it.jsonObject }
                    ?.firstOrNull { (it["name"]?.jsonPrimitive?.contentOrNull ?: "").endsWith(".apk", ignoreCase = true) } ?: continue
                val url = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: continue
                if (!url.startsWith("https://github.com/$repo/releases/download/")) continue
                best = UpdateInfo(
                    version = v.joinToString("."), tag = tag, repo = repo,
                    assetName = apk["name"]!!.jsonPrimitive.content, downloadUrl = url,
                    sizeBytes = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    sha256 = apk["digest"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:")?.lowercase()?.takeIf { Regex("[0-9a-f]{64}").matches(it) },
                    notes = r["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                )
                bestVersion = v
            }
        }
        best
    }

    override fun download(info: UpdateInfo, onProgress: (Float) -> Unit): Path = runBlocking {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "WsMusic-${info.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val response = InnerTubeXStream.httpClient.get(info.downloadUrl) { header("User-Agent", "WsMusic-android-updater") }
            check(response.status.value == 200) { "Download failed: HTTP ${response.status.value}" }
            val channel = response.bodyAsChannel()
            var done = 0L
            val buffer = ByteArray(64 * 1024)
            file.outputStream().use { out ->
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) { if (channel.isClosedForRead) break else continue }
                    out.write(buffer, 0, n); digest.update(buffer, 0, n); done += n
                    if (info.sizeBytes > 0) onProgress((done.toFloat() / info.sizeBytes).coerceIn(0f, 1f))
                }
            }
            if (info.sizeBytes > 0) check(file.length() == info.sizeBytes) { "The download is incomplete" }
            info.sha256?.let { expected ->
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                check(got == expected) { "The download does not match its checksum, so it was discarded" }
            }
            file.toPath()
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    override fun launchInstaller(file: Path) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file.toFile())
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
