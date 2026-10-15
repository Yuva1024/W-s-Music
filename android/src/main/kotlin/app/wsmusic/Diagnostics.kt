package app.wsmusic

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The last things the player and the loader did, shown under "Details" so problems on a phone can be read off the screen. */
object Diagnostics {
    val lines = mutableStateListOf<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(text: String) {
        val line = time.format(Date()) + "  " + text
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            lines.add(line)
            while (lines.size > 40) lines.removeAt(0)
        }
    }
}
