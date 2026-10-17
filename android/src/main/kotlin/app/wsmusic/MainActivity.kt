package app.wsmusic

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import app.wsmusic.ui.PhoneApp
import app.wsmusic.ui.WsColors
import echopc.SpotifyLink

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) // for the playback notification
        val controller = WsApp.instance.controller
        if (savedInstanceState == null) handleShare(intent)
        setContent { MaterialTheme(colorScheme = WsColors) { PhoneApp(controller) } }
    }

    override fun onResume() {
        super.onResume()
        WsApp.instance.controller.sync.syncSoon(800) // catch up with the computer whenever the app comes back
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** A Spotify playlist or album shared to the app opens the import dialog with its link filled in. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val controller = WsApp.instance.controller
        if (SpotifyLink.looksLikeLink(text)) {
            controller.spotifyImportDraft = text.trim()
            controller.spotifyImportOpen = true
        } else {
            controller.showToast("Share a Spotify playlist or album link to import it")
        }
    }
}
