package echopc

import com.music.innertube.models.Album
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import java.io.File
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

/**
 * Music files on this PC: the folders the person chose (the Windows Music folder to begin with), read with their
 * tags and cover art. What was read is remembered by file and change time, so later scans are quick.
 */
object DesktopLocalMusic : LocalMusicSource {
    /** What the desktop player can play (JavaFX Media). */
    private val extensions = setOf("mp3", "m4a", "aac", "wav", "aif", "aiff")
    private val base = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "WsMusic").also { it.mkdirs() }
    private val foldersFile = File(base, "music-folders.txt")
    private val indexFile = File(base, "music-index.tsv")
    private val artDir = File(base, "art")
    private val quiet: Logger = Logger.getLogger("org.jaudiotagger").apply { level = Level.OFF } // it logs every file otherwise

    fun defaultFolders(): List<File> = listOf(File(System.getProperty("user.home"), "Music")).filter { it.isDirectory }

    var folders: List<File>
        get() = if (foldersFile.isFile) foldersFile.readLines().filter { it.isNotBlank() }.map(::File) else defaultFolders()
        set(value) { foldersFile.writeText(value.distinctBy { it.absolutePath.lowercase() }.joinToString("\n") { it.absolutePath }) }

    fun addFolder(dir: File) { folders = folders + dir }
    fun removeFolder(dir: File) { folders = folders.filter { !it.absolutePath.equals(dir.absolutePath, ignoreCase = true) } }

    private data class Entry(val mtime: Long, val song: SongItem)

    override suspend fun scan(): List<SongItem> = withContext(Dispatchers.IO) {
        quiet.level = Level.OFF
        val index = readIndex()
        val files = folders.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown().maxDepth(12).filter { it.isFile && it.extension.lowercase() in extensions }.toList()
        }.distinctBy { it.absolutePath }
        val songs = files.map { f ->
            val known = index[f.absolutePath]
            if (known != null && known.mtime == f.lastModified()) known.song
            else read(f).also { index[f.absolutePath] = Entry(f.lastModified(), it) }
        }
        writeIndex(index.filterKeys { path -> files.any { it.absolutePath == path } })
        songs.sortedBy { it.title.lowercase() }
    }

    private fun read(file: File): SongItem {
        val fromName = file.nameWithoutExtension.split(" - ", limit = 2).map { it.trim() }
        var title = if (fromName.size == 2) fromName[1] else file.nameWithoutExtension
        var artist = if (fromName.size == 2) fromName[0] else ""
        var album = ""
        var seconds: Int? = null
        var thumb = ""
        runCatching {
            val audio = AudioFileIO.read(file)
            seconds = audio.audioHeader?.trackLength?.takeIf { it > 0 }
            audio.tag?.let { tag ->
                tag.getFirst(FieldKey.TITLE)?.takeIf { it.isNotBlank() }?.let { title = it.trim() }
                tag.getFirst(FieldKey.ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it.trim() }
                album = tag.getFirst(FieldKey.ALBUM)?.trim().orEmpty()
                tag.firstArtwork?.binaryData?.takeIf { it.isNotEmpty() }?.let { bytes ->
                    artDir.mkdirs()
                    val out = File(artDir, hash(bytes) + ".img")
                    if (!out.isFile) out.writeBytes(bytes)
                    thumb = out.toURI().toString()
                }
            }
        }
        return SongItem(
            id = OfflineFiles.LOCAL_PREFIX + file.absolutePath, title = title,
            artists = listOf(Artist(artist.ifBlank { "Unknown artist" }, null)),
            album = album.takeIf { it.isNotBlank() }?.let { Album(it, "local-album:$it") },
            duration = seconds, thumbnail = thumb,
        )
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).take(10).joinToString("") { "%02x".format(it) }

    // path, mtime, title, artist, album, seconds, thumbnail (tabs and newlines in tags become spaces)
    private fun readIndex(): MutableMap<String, Entry> = runCatching {
        indexFile.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 7) return@mapNotNull null
            p[0] to Entry(p[1].toLongOrNull() ?: 0, SongItem(
                id = OfflineFiles.LOCAL_PREFIX + p[0], title = p[2], artists = listOf(Artist(p[3], null)),
                album = p[4].takeIf { it.isNotBlank() }?.let { Album(it, "local-album:$it") }, duration = p[5].toIntOrNull(), thumbnail = p[6],
            ))
        }.toMap(HashMap())
    }.getOrDefault(HashMap())

    private fun writeIndex(index: Map<String, Entry>) = runCatching {
        fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        indexFile.writeText(index.entries.joinToString("\n") { (path, e) ->
            listOf(path, e.mtime.toString(), clean(e.song.title), clean(e.song.artists.firstOrNull()?.name.orEmpty()),
                clean(e.song.album?.name.orEmpty()), e.song.duration?.toString().orEmpty(), e.song.thumbnail).joinToString("\t")
        })
    }
}
