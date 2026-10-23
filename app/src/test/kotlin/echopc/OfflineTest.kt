package echopc

import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Downloads and music files on this device play from their file, without streaming. */
class OfflineTest {
    private fun tmpDir() = Files.createTempDirectory("ws-offline").toFile().also { it.deleteOnExit() }

    /** One second of silence as a WAV file. */
    private fun wav(file: File) {
        val rate = 8000; val samples = rate
        RandomAccessFile(file, "rw").use { f ->
            fun i32(v: Int) { f.write(v and 0xff); f.write(v shr 8 and 0xff); f.write(v shr 16 and 0xff); f.write(v shr 24 and 0xff) }
            fun i16(v: Int) { f.write(v and 0xff); f.write(v shr 8 and 0xff) }
            f.writeBytes("RIFF"); i32(36 + samples * 2); f.writeBytes("WAVEfmt "); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
            f.writeBytes("data"); i32(samples * 2); f.write(ByteArray(samples * 2))
        }
    }

    @Test
    fun downloadedAndLocalFilesPlayWithoutStreaming() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val downloads = tmpDir()
        AppHooks.downloadsDir = downloads
        try {
            val repo = LibraryRepository(LibraryDatabase.open(File.createTempFile("ws-off", ".db").also { it.delete(); it.deleteOnExit() }))
            val c = Controller(scope, repo, null) { _, _ -> FakeEngine() }
            // The loader would fail for this id if it tried to stream (it's not a real video).
            val loader = AudioLoader(resolver = { _, _, _ -> error("must not stream") })

            // A downloaded song: its file is used, and it shows up as downloaded once the library says so.
            val song = fakeSong(1, "Saved Song", "Someone")
            File(downloads, "${song.id}.m4a").writeBytes(ByteArray(64))
            repo.setDownloaded(song, true)
            withTimeout(10_000) { while (!c.downloads.isDownloaded(song.id)) delay(25) }
            assertEquals(File(downloads, "${song.id}.m4a").toPath(), loader.load(song.id).await())

            // A music file on this computer: found in its folder, named from "Artist - Title", played from the file.
            val music = tmpDir()
            val file = File(music, "Tame Impala - Let It Happen.wav").also(::wav)
            DesktopLocalMusic.folders = listOf(music)
            val found = DesktopLocalMusic.scan().single()
            assertEquals("Let It Happen", found.title)
            assertEquals("Tame Impala", found.artists.single().name)
            assertEquals(1, found.duration)
            assertEquals(file.toPath(), loader.load(found.id).await())

            // Removing the file: a clear message instead of streaming something else.
            file.delete()
            assertFailsWith<java.io.FileNotFoundException> { loader.load(found.id).await() }

            // Removing a download deletes the file.
            c.downloads.remove(song)
            withTimeout(10_000) { while (c.downloads.isDownloaded(song.id)) delay(25) }
            assertTrue(!File(downloads, "${song.id}.m4a").exists())
        } finally {
            scope.cancel()
            AppHooks.downloadsDir = null
            DesktopLocalMusic.folders = DesktopLocalMusic.defaultFolders()
        }
    }
}
