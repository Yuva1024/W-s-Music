package echopc

import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import echopc.sync.PhonePairing
import echopc.sync.SyncCategory
import echopc.sync.SyncManager
import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A computer and a phone, each with its own database, pairing and syncing over a real local connection. */
class SyncTest {
    private fun tmpDb() = File.createTempFile("ws-sync", ".db").also { it.delete(); it.deleteOnExit() }
    private suspend fun until(what: String, check: suspend () -> Boolean) = withTimeout(15_000) { while (!check()) delay(25) }.also { println("ok: $what") }
    private fun song(id: String) = SongItem(id = id, title = "Song $id", artists = listOf(Artist("Artist $id", "UC$id")), duration = 200, thumbnail = "")

    private suspend fun LibraryRepository.likedNow() = likedSongs.first().map { it.song.id }.toSet()
    private suspend fun LibraryRepository.playlistByName(name: String) = playlists.first().firstOrNull { it.playlist.name == name }
    private suspend fun LibraryRepository.songsIn(name: String) =
        playlistByName(name)?.let { p -> playlistSongs(p.playlist.id).first().map { it.song.song.id } }.orEmpty()
    private suspend fun LibraryRepository.signalSongs() = signalsSince(LocalDateTime.now().minusYears(1)).map { it.songId }

    /** Libraries from 1.0.5 and earlier (schema v3) get the sync tables and keep everything else. */
    @Test
    fun upgradesExistingLibrary() = runBlocking {
        val file = tmpDb()
        LibraryRepository(LibraryDatabase.open(file)).apply { toggleLike(song("old")); close() }
        val conn = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(file.absolutePath)
        try {
            listOf("DROP TABLE sync_op", "DROP TABLE sync_kv", "PRAGMA user_version = 3").forEach { sql -> conn.prepare(sql).use { it.step() } }
        } finally { conn.close() }
        val repo = LibraryRepository(LibraryDatabase.open(file))
        assertEquals(setOf("old"), repo.likedNow())
        val store = echopc.sync.SyncStore(repo)
        store.reconcile()
        assertTrue(store.have().isNotEmpty())
        repo.close()
    }

    @Test
    fun pairsAndSyncsBothWays() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pcLib = LibraryRepository(LibraryDatabase.open(tmpDb()))
        val phoneLib = LibraryRepository(LibraryDatabase.open(tmpDb()))
        AppHooks.deviceName = { "Test device" }
        try {
            // Before pairing, each has its own library.
            pcLib.toggleLike(song("p1"))
            val road = pcLib.createPlaylist("Road")
            pcLib.addToPlaylist(road.id, song("p1")); pcLib.addToPlaylist(road.id, song("p2"))
            pcLib.recordSignal(song("p2"), 200_000, 200_000, "clickrow", "trackdone", "search")
            pcLib.recordPlay(song("p2"), 200_000)
            phoneLib.toggleLike(song("m1"))
            val gym = phoneLib.createPlaylist("Gym")
            phoneLib.addToPlaylist(gym.id, song("m1"))

            val pc = SyncManager(pcLib, scope, serverFactory = { DesktopSyncServer(it) })
            val phone = SyncManager(phoneLib, scope, serverFactory = null)
            pc.start(); phone.start()
            delay(300)

            // Pairing: both screens show the same code; the phone confirms, the computer allows.
            pc.openPairing()
            until("computer listening") { pc.addresses.isNotEmpty() || pc.port > 0 }
            phone.pairWith(phone.manualPc("127.0.0.1:${pc.port}")!!)
            until("codes shown") { phone.pairing is PhonePairing.Confirm && pc.pairRequest != null }
            assertEquals(pc.pairRequest!!.code, (phone.pairing as PhonePairing.Confirm).code)
            phone.confirmCode()
            pc.allowPair()
            until("paired") { phone.peers.isNotEmpty() && pc.peers.isNotEmpty() }
            phone.syncOnce()
            assertTrue(phone.status!!.startsWith("Synced"), phone.status)

            assertEquals(setOf("p1", "m1"), phoneLib.likedNow())
            assertEquals(setOf("p1", "m1"), pcLib.likedNow())
            assertEquals(listOf("p1", "p2"), phoneLib.songsIn("Road"))
            assertEquals(listOf("m1"), pcLib.songsIn("Gym"))
            assertTrue("p2" in phoneLib.signalSongs())
            assertEquals(1, phoneLib.history(10).first().size)

            // Changes on both sides after pairing, removals included.
            phoneLib.toggleLike(song("p1")) // unliked on the phone
            phoneLib.deletePlaylist(phoneLib.playlistByName("Road")!!.playlist.id)
            pcLib.renamePlaylist(pcLib.playlistByName("Gym")!!.playlist.id, "Gym 2")
            val gymOnPc = pcLib.playlistByName("Gym 2")!!.playlist.id
            pcLib.addToPlaylist(gymOnPc, song("p3"))
            pcLib.removeFromPlaylist(pcLib.playlistSongs(gymOnPc).first().first { it.song.song.id == "m1" }.map)
            phone.syncOnce()

            assertEquals(setOf("m1"), pcLib.likedNow())
            assertNull(pcLib.playlistByName("Road"))
            assertEquals(listOf("p3"), phoneLib.songsIn("Gym 2"))
            assertNull(phoneLib.playlistByName("Gym"))

            // Music taste switched off on the phone: plays stay on the computer until it's switched on again.
            phone.setSwitch(SyncCategory.TASTE, false)
            delay(300)
            pcLib.recordSignal(song("p4"), 200_000, 200_000, "clickrow", "trackdone", "search")
            pcLib.toggleLike(song("p5"))
            phone.syncOnce()
            assertFalse("p4" in phoneLib.signalSongs())
            assertTrue("p5" in phoneLib.likedNow()) // the library still syncs
            phone.setSwitch(SyncCategory.TASTE, true)
            delay(300)
            phone.syncOnce()
            assertTrue("p4" in phoneLib.signalSongs())

            // Syncing again duplicates nothing.
            phone.syncOnce(); phone.syncOnce()
            assertEquals(pcLib.signalSongs().sorted(), phoneLib.signalSongs().sorted())
            assertEquals(1, phoneLib.history(10).first().count { it.song.song.id == "p2" })
            assertEquals(1, pcLib.playlists.first().count { it.playlist.name == "Gym 2" })

            // After the computer forgets the phone, the phone gets nothing.
            pc.unpair(pc.peers.single().id)
            delay(300)
            phone.syncOnce()
            assertFalse(phone.status!!.startsWith("Synced"), phone.status) // with no phone left the computer stops listening
            pc.stop()
        } finally {
            scope.cancel()
            AppHooks.deviceName = { "W's MUSIC" }
        }
    }
}
