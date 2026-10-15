package app.wsmusic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import echopc.InnerTubeXStream
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Album-art helpers: smaller pictures for small places, and the colour the player background takes from the art. */
object Artwork {
    private val sizeRegex = Regex("=w\\d+-h\\d+[^/?]*$")

    /** googleusercontent thumbnails accept a size suffix; other hosts are used as they are. */
    fun sized(url: String, px: Int): String =
        if (sizeRegex.containsMatchIn(url)) url.replace(sizeRegex, "=w$px-h$px-l90-rj") else url

    private val cache = object : LinkedHashMap<String, Color>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?) = size > 200
    }

    /** A vivid colour from the picture (average of its colourful pixels), or null if it can't be read. */
    suspend fun dominant(url: String): Color? {
        synchronized(cache) { cache[url] }?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = InnerTubeXStream.httpClient.get(sized(url, 60)).readRawBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                val small = Bitmap.createScaledBitmap(bmp, 24, 24, true)
                var r = 0L; var g = 0L; var b = 0L; var n = 0L
                var ar = 0L; var ag = 0L; var ab = 0L
                val hsv = FloatArray(3)
                for (y in 0 until small.height) for (x in 0 until small.width) {
                    val p = small.getPixel(x, y)
                    val pr = (p shr 16) and 255; val pg = (p shr 8) and 255; val pb = p and 255
                    ar += pr; ag += pg; ab += pb
                    android.graphics.Color.RGBToHSV(pr, pg, pb, hsv)
                    if (hsv[1] > 0.25f && hsv[2] > 0.2f) { r += pr; g += pg; b += pb; n++ }
                }
                val count = (small.width * small.height).toLong()
                val color = if (n > count / 10) Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
                else Color((ar / count).toInt(), (ag / count).toInt(), (ab / count).toInt())
                synchronized(cache) { cache[url] = color }
                color
            }.getOrNull()
        }
    }
}
