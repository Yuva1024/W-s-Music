package app.wsmusic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import com.music.innertube.models.Album
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import echopc.LocalMusicSource
import echopc.OfflineFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The phone's music library (Android's MediaStore): every song file with its title, artist, album and cover. */
class AndroidLocalMusic(private val context: Context) : LocalMusicSource {
    companion object {
        val permission: String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun allowed(): Boolean = Build.VERSION.SDK_INT < 23 || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override suspend fun scan(): List<SongItem> = withContext(Dispatchers.IO) {
        if (!allowed()) throw SecurityException("W's MUSIC may not read music on this phone yet")
        val columns = arrayOf(
            MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA,
        )
        val songs = ArrayList<SongItem>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
        )?.use { cur ->
            while (cur.moveToNext()) {
                val path = cur.getString(5) ?: continue
                val title = cur.getString(0)?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/').substringBeforeLast('.')
                val artist = cur.getString(1)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist"
                val album = cur.getString(2)?.takeIf { it.isNotBlank() }
                songs += SongItem(
                    id = OfflineFiles.LOCAL_PREFIX + path, title = title, artists = listOf(Artist(artist, null)),
                    album = album?.let { Album(it, "local-album:$it") }, duration = (cur.getLong(4) / 1000).toInt().takeIf { it > 0 },
                    thumbnail = "content://media/external/audio/albumart/${cur.getLong(3)}",
                )
            }
        }
        songs
    }
}
