package app.wsmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First step of the phone app: search YouTube Music through the code shared with the desktop app. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { SearchProbe() }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SearchProbe() {
    var query by remember { mutableStateOf("tame impala") }
    var lines by remember { mutableStateOf(listOf<String>()) }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("W's MUSIC", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true)
            Button({
                scope.launch {
                    lines = listOf("Searching…")
                    lines = withContext(Dispatchers.IO) {
                        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).fold(
                            { r -> r.items.filterIsInstance<SongItem>().map { "${it.title} - ${it.artists.joinToString { a -> a.name }}" } },
                            { e -> listOf("Search failed: ${e.message}") },
                        )
                    }
                }
            }) { Text("Search") }
        }
        LazyColumn { items(lines) { Text(it, Modifier.padding(vertical = 6.dp)) } }
    }
}
