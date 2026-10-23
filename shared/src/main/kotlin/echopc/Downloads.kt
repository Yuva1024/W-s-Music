package echopc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.innertube.models.SongItem
import echopc.db.LibraryRepository
import echopc.db.toSongItem
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Songs saved for offline listening, in the app's own folder ([AppHooks.downloadsDir]): they play without
 * internet and never wait for YouTube. Two download at a time; the rest wait in line.
 */
class Downloads(private val library: LibraryRepository, private val scope: CoroutineScope, private val onDone: (String) -> Unit = {}) {
    /** Ids waiting or downloading right now (value: "queued" or "downloading"). */
    val active = mutableStateMapOf<String, String>()
    var downloadedIds by mutableStateOf<Set<String>>(emptySet()); private set
    var downloadedSongs by mutableStateOf<List<SongItem>>(emptyList()); private set

    private val loader = AudioLoader()
    private val queue = Channel<SongItem>(Channel.UNLIMITED)
    private var failed = 0

    private val dir: File? get() = AppHooks.downloadsDir?.also { it.mkdirs() }

    /** The saved file of a downloaded song (null if it isn't downloaded here). */
    fun fileFor(id: String): File? = dir?.let { File(it, "${safe(id)}.m4a") }?.takeIf { it.isFile }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun start() {
        scope.launch {
            library.downloadedSongs.collect { rows ->
                // A file deleted outside the app: the song is no longer downloaded.
                val (present, missing) = rows.partition { fileFor(it.song.id) != null }
                missing.forEach { library.setDownloaded(it.toSongItem(), false) }
                downloadedSongs = present.map { it.toSongItem() }
                downloadedIds = downloadedSongs.map { it.id }.toSet()
            }
        }
        repeat(2) { scope.launch(Dispatchers.IO) { for (song in queue) fetch(song) } }
    }

    fun isDownloaded(id: String) = id in downloadedIds

    /** Saves [songs] for offline (songs already saved, waiting, or on this device anyway are skipped). */
    fun download(songs: List<SongItem>) {
        val todo = songs.filter { !OfflineFiles.isLocal(it.id) && it.id !in downloadedIds && it.id !in active }.distinctBy { it.id }
        if (todo.isEmpty()) return
        if (active.isEmpty()) failed = 0
        todo.forEach { active[it.id] = "queued"; queue.trySend(it) }
    }

    fun cancelAll() {
        active.keys.toList().forEach { active.remove(it) }
    }

    /** Deletes the saved file; the song still plays by streaming. */
    fun remove(song: SongItem) {
        active.remove(song.id)
        scope.launch(Dispatchers.IO) {
            fileFor(song.id)?.delete()
            library.setDownloaded(song, false)
        }
    }

    private suspend fun fetch(song: SongItem) {
        if (song.id !in active) return // cancelled while waiting
        val target = dir?.let { File(it, "${safe(song.id)}.m4a") } ?: run { active.remove(song.id); return }
        active[song.id] = "downloading"
        val ok = runCatching {
            val temp = loader.stream(song.id).await()
            val part = File(target.path + ".part")
            Files.copy(temp, part.toPath(), StandardCopyOption.REPLACE_EXISTING)
            loader.release(song.id)
            runCatching { Files.deleteIfExists(temp) }
            if (song.id !in active) { part.delete(); return } // cancelled meanwhile
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            library.setDownloaded(song, true)
        }.onFailure { AppHooks.log("Download of ${song.id} failed: ${it.message?.take(160)}") }.isSuccess
        if (!ok) failed++
        active.remove(song.id)
        if (active.isEmpty()) onDone(if (failed == 0) "Downloads finished" else "Downloads finished; $failed couldn't be saved (try again later)")
    }
}
