package app.wsmusic

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import app.wsmusic.ui.PhoneApp
import app.wsmusic.ui.WsColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) // for the playback notification
        val controller = WsApp.instance.controller
        setContent { MaterialTheme(colorScheme = WsColors) { PhoneApp(controller) } }
    }
}
