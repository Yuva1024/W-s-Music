package echopc

import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class SpotifyImportTest {
    private fun tmpDb() = File.createTempFile("ws-spotify", ".db").also { it.delete(); it.deleteOnExit() }

    @Test
    fun readsLinks() {
        assertEquals(SpotifyLink.Target("playlist", "37i9dQZF1DXcBWIGoYBM5M"), SpotifyLink.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123"))
        assertEquals(SpotifyLink.Target("album", "4aawyAB9vmqN3uQ7FjRGTy"), SpotifyLink.parse("Check this https://open.spotify.com/intl-de/album/4aawyAB9vmqN3uQ7FjRGTy"))
        assertEquals(SpotifyLink.Target("playlist", "37i9dQZF1DXcBWIGoYBM5M"), SpotifyLink.parse("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(SpotifyLink.parse("https://music.youtube.com/playlist?list=PL123"))
        assertTrue(SpotifyLink.looksLikeLink("https://spotify.link/AbCdEf"))
    }

    @Test
    fun readsEmbedPage() {
        val html = """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"type":"playlist","name":"Road Trip",
            "trackList":[{"uri":"spotify:track:1","title":"Expectations (feat. Chappell Roan)","subtitle":"Olivia Rodrigo, Chappell Roan","duration":223935,"entityType":"track"},
            {"uri":"spotify:episode:2","title":"A podcast","subtitle":"Someone","duration":1000,"entityType":"episode"}]}}}}}}</script></html>"""
        val export = SpotifyLink.parseEmbed(html, "playlist")
        val c = export.collections.single()
        assertEquals("Road Trip", c.name)
        assertEquals("playlist", c.kind)
        assertEquals(listOf("Olivia Rodrigo", "Chappell Roan"), c.tracks.single().artists)
        assertEquals(223, c.tracks.single().durationSec)
        assertTrue(export.notes.isEmpty())
    }

    private val playlistJson = """{"playlists":[{"name":"Gym","lastModifiedDate":"2024-01-01","items":[
        {"track":{"trackName":"Song A","artistName":"Artist One","albumName":"Album","trackUri":"spotify:track:a"},"episode":null},
        {"track":null,"episode":{"episodeName":"Talk"}},
        {"track":{"trackName":"Song B","artistName":"Artist Two","albumName":"Album","trackUri":"spotify:track:b"}}]},
        {"name":"Empty","items":[]}]}"""
    private val libraryJson = """{"tracks":[{"artist":"Artist One","album":"Album","track":"Song C","uri":"spotify:track:c"}],"artists":[{"name":"Artist Three","uri":"x"}],"albums":[]}"""
    private val historyJson = """[
        {"ts":"2024-05-01T10:00:00Z","ms_played":200000,"master_metadata_track_name":"Song D","master_metadata_album_artist_name":"Artist Four","spotify_track_uri":"spotify:track:d"},
        {"ts":"2024-05-02T10:00:00Z","ms_played":180000,"master_metadata_track_name":"Song D","master_metadata_album_artist_name":"Artist Four","spotify_track_uri":"spotify:track:d"},
        {"ts":"2024-05-03T10:00:00Z","ms_played":5000,"master_metadata_track_name":"Song E","master_metadata_album_artist_name":"Artist Five","spotify_track_uri":"spotify:track:e"}]"""

    @Test
    fun readsDataDownloadZip() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                listOf("Spotify Account Data/Playlist1.json" to playlistJson, "Spotify Account Data/YourLibrary.json" to libraryJson,
                    "Spotify Extended Streaming History/Streaming_History_Audio_2024.json" to historyJson, "Spotify Account Data/Userdata.json" to "{}",
                ).forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            }
        }.toByteArray()
        val export = SpotifyFiles.read("my_spotify_data.zip", zip)
        val byKind = export.collections.groupBy { it.kind }
        assertEquals(listOf("Gym"), byKind["playlist"]!!.map { it.name }) // the empty playlist is skipped
        assertEquals(listOf("Song A", "Song B"), byKind["playlist"]!!.single().tracks.map { it.title })
        assertEquals(listOf("Song C"), byKind["liked"]!!.single().tracks.map { it.title })
        assertEquals(listOf("Artist Three"), byKind["artists"]!!.single().artists)
        val listened = byKind["listened"]!!.single().tracks
        assertEquals(listOf("Song D"), listened.map { it.title }) // Song E: under 30 s, and played once only
        assertEquals(2, listened.single().plays)
    }

    @Test
    fun rejectsOtherFiles() {
        assertFailsWith<IllegalArgumentException> { SpotifyFiles.read("notes.txt", "hello".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { SpotifyFiles.read("x.json", """{"something":1}""".toByteArray()) }
    }

    private fun song(id: String, title: String, artist: String, seconds: Int = 200) =
        SongItem(id = id, title = title, artists = listOf(Artist(artist, "UC$artist")), duration = seconds, thumbnail = "")

    @Test
    fun importsIntoLibraryAndTaste() = runBlocking {
        val repo = LibraryRepository(LibraryDatabase.open(tmpDb()))
        val catalog = listOf(song("yA", "Song A", "Artist One"), song("yB", "Song B", "Artist Two"), song("yC", "Song C", "Artist One"), song("yD", "Song D", "Artist Four"))
        val importer = SpotifyImporter(repo, searchSongs = { q -> catalog.filter { q.startsWith(it.title) } }, searchArtist = { null })
        val export = SpotifyExport(listOf(
            SpotifyCollection("playlist", "Gym", listOf(SpotifyTrack("a", "Song A", listOf("Artist One")), SpotifyTrack("b", "Song B", listOf("Artist Two")), SpotifyTrack("z", "Not On YouTube", listOf("Nobody")))),
            SpotifyCollection("liked", "Liked Songs", listOf(SpotifyTrack("c", "Song C", listOf("Artist One")))),
            SpotifyCollection("listened", "Listening history", listOf(SpotifyTrack("d", "Song D", listOf("Artist Four"), plays = 3, lastPlayed = LocalDateTime.now().minusDays(1)))),
        ))
        val summary = importer.import(export)
        assertEquals(5, summary.uniqueTracks)
        assertEquals(4, summary.matched)
        assertEquals(listOf("Not On YouTube - Nobody"), summary.notFound)

        val gym = repo.playlists.first().single { it.playlist.name == "Gym" }
        assertEquals(2, gym.songCount)
        assertEquals(listOf("yC"), repo.likedSongs.first().map { it.song.id })
        assertEquals(3, repo.signalsSince(LocalDateTime.now().minusDays(30)).count { it.songId == "yD" })

        // Importing the same playlist again fills the existing one instead of making a copy.
        importer.import(SpotifyExport(listOf(SpotifyCollection("playlist", "gym", listOf(SpotifyTrack("a", "Song A", listOf("Artist One")))))))
        assertEquals(1, repo.playlists.first().count { it.playlist.name.equals("gym", ignoreCase = true) })
        assertTrue(summary.text().contains("shape your taste"))
    }
}
