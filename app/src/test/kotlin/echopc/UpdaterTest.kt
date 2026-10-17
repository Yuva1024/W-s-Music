package echopc

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Self-update: finding the newest release in either repository, trusting only its own files, and the dialog. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class UpdaterTest {
    private val fork = Updater.repos[0]
    private val main = Updater.repos[1]

    private fun release(repo: String, tag: String, asset: String = "WsMusic-Setup.exe", extra: String = "", url: String? = null, digest: String? = null) =
        """{"tag_name":"$tag","body":"Notes for $tag","draft":false,"prerelease":false$extra,"assets":[
            {"name":"WsMusic-portable.zip","size":5,"browser_download_url":"https://github.com/$repo/releases/download/$tag/WsMusic-portable.zip"},
            {"name":"$asset","size":123,"browser_download_url":"${url ?: "https://github.com/$repo/releases/download/$tag/$asset"}"${if (digest != null) ""","digest":"sha256:$digest"""" else ""}}
        ]}"""

    @Test
    fun versionsAreParsedAndCompared() {
        assertEquals(listOf(1, 0, 2), Updater.parseVersion("v1.0.2"))
        assertEquals(listOf(1, 0, 0), Updater.parseVersion("v1.0.0-lowend"))
        assertNull(Updater.parseVersion("nightly"))
        assertTrue(Updater.isNewer(listOf(1, 0, 10), listOf(1, 0, 9)))
        assertTrue(Updater.isNewer(listOf(1, 1, 0), listOf(1, 0, 99)))
        assertTrue(!Updater.isNewer(listOf(1, 0, 2), listOf(1, 0, 2)))
        assertTrue(!Updater.isNewer(listOf(1, 0, 1), listOf(1, 0, 2)))
    }

    @Test
    fun theNewestReleaseInEitherRepositoryWins() {
        val both = mapOf(fork to release(fork, "v1.0.3"), main to release(main, "v1.0.4"))
        assertEquals(main, Updater.choose(both, "1.0.2")?.repo)
        assertEquals("1.0.4", Updater.choose(both, "1.0.2")?.version)
        // Same version in both: the fork (first in the list) is used.
        val same = mapOf(fork to release(fork, "v1.0.3"), main to release(main, "v1.0.3"))
        assertEquals(fork, Updater.choose(same, "1.0.2")?.repo)
        // Only one repository answered.
        assertEquals(fork, Updater.choose(mapOf(fork to release(fork, "v1.0.3")), "1.0.2")?.repo)
    }

    @Test
    fun nothingIsOfferedWhenYouAreCurrentOrAhead() {
        assertNull(Updater.choose(mapOf(fork to release(fork, "v1.0.2")), "1.0.2"))
        assertNull(Updater.choose(mapOf(fork to release(fork, "v1.0.1")), "1.0.2"))
        assertNull(Updater.choose(emptyMap(), "1.0.2"))
        assertNull(Updater.choose(mapOf(fork to "not json"), "1.0.2"))
    }

    @Test
    fun draftsPrereleasesAndForeignDownloadsAreIgnored() {
        val draft = release(fork, "v1.0.5").replace("\"draft\":false", "\"draft\":true")
        val pre = release(fork, "v1.0.5").replace("\"prerelease\":false", "\"prerelease\":true")
        assertNull(Updater.choose(mapOf(fork to draft), "1.0.2"))
        assertNull(Updater.choose(mapOf(fork to pre), "1.0.2"))
        // A release whose installer lives anywhere but this repository's own release files is refused.
        assertNull(Updater.choose(mapOf(fork to release(fork, "v1.0.5", url = "https://evil.example.com/WsMusic-Setup.exe")), "1.0.2"))
        assertNull(Updater.choose(mapOf(fork to release(fork, "v1.0.5", url = "https://github.com/someone-else/W-s-Music/releases/download/v1.0.5/WsMusic-Setup.exe")), "1.0.2"))
    }

    @Test
    fun theInstallerIsPickedAndItsChecksumIsKept() {
        val digest = "a".repeat(64)
        val info = assertNotNull(Updater.choose(mapOf(fork to release(fork, "v1.0.3", asset = "WsMusic-lowend-setup.exe", digest = digest)), "1.0.2"))
        assertEquals("WsMusic-lowend-setup.exe", info.assetName) // not the portable zip
        assertEquals(digest, info.sha256)
        assertEquals("Notes for v1.0.3", info.notes)
        // A malformed digest is not trusted.
        assertNull(Updater.choose(mapOf(fork to release(fork, "v1.0.3", digest = "zz")), "1.0.2")?.sha256)
    }

    @Test
    fun theSilentMsiIsPreferredOverTheSetup() {
        val body = """{"tag_name":"v1.0.8","body":"","draft":false,"prerelease":false,"assets":[
            {"name":"WsMusic-Setup.exe","size":5,"browser_download_url":"https://github.com/$fork/releases/download/v1.0.8/WsMusic-Setup.exe"},
            {"name":"WsMusic-1.0.8.msi","size":6,"browser_download_url":"https://github.com/$fork/releases/download/v1.0.8/WsMusic-1.0.8.msi"}]}"""
        val info = assertNotNull(Updater.choose(mapOf(fork to body), "1.0.7"))
        assertEquals("WsMusic-1.0.8.msi", info.assetName)
        // Run from the build or tests (not the installed WsMusic.exe) it falls back to the installer window.
        assertEquals(false, Updater.installsSilently(info))
    }

    @Test
    fun checksumOfAFileIsComputed() {
        val f = Files.createTempFile("ws-sha", ".bin").also { it.toFile().deleteOnExit() }
        Files.write(f, "abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Updater.sha256Hex(f))
    }

    @Test
    fun thisBuildKnowsItsOwnVersion() {
        val v = Updater.currentVersion()
        assertTrue(Updater.parseVersion(v) != null, "version.properties gives a real version, got '$v'")
    }

    @Test
    fun theDialogTextShowsTheUpdate() = runDesktopComposeUiTest(width = 900, height = 600, testTimeout = 2.minutes) {
        val repo = LibraryRepository(LibraryDatabase.open(File.createTempFile("ws-upd", ".db").also { it.delete(); it.deleteOnExit() }))
        val scope = TestScope()
        val c = Controller(scope, repo, null) { _, _ -> FakeEngine() }
        val info = UpdateInfo("9.9.9", "v9.9.9", fork, "WsMusic-Setup.exe", "https://github.com/$fork/releases/download/v9.9.9/WsMusic-Setup.exe", 1, null, "Faster search")
        c.update = info
        // The body only (the dialog window itself is not opened in tests: leftover dialog windows disturb later UI tests).
        setContent { WsTheme(c.accent) { UpdateDialogBody(c, info) } }
        for (text in listOf("Version 9.9.9 is ready", "Faster search", "kept")) {
            try { waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() } }
            catch (e: Throwable) { throw AssertionError("missing on screen: $text", e) }
        }
        c.updateProgress = 0.5f
        waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText("Downloading… 50%").fetchSemanticsNodes().isNotEmpty() }
        c.updateProgress = null; c.updateError = "The download does not match its checksum"
        waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText("Update failed", substring = true).fetchSemanticsNodes().isNotEmpty() }
        scope.stop()
        repo.close()
    }
}
