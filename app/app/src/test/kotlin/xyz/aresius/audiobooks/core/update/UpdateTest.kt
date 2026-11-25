package xyz.aresius.audiobooks.core.update

import java.io.File
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import okio.Buffer
import xyz.aresius.audiobooks.core.network.AppUpdateDto
import xyz.aresius.audiobooks.testutil.FakeServer

class UpdateTest {
    private val server = FakeServer()
    private val dir = kotlin.io.path.createTempDirectory("updates").toFile()
    private val files = UpdateFiles(File(dir, "updates"))
    private val apkBytes = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }
    private val info = AppUpdateInfo("0.4.0", 400, apkBytes.size.toLong(), sha)

    @AfterTest fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun downloader(free: Long = Long.MAX_VALUE) = ApkDownloader(OkHttpClient(), files, freeSpace = { free })

    private fun serve(bytes: ByteArray = apkBytes, code: Int = 200) =
        server.on("GET", "/v1/app/update/apk", MockResponse.Builder().code(code).body(Buffer().write(bytes)).build())

    private fun dto(version: String = "0.4.0", code: Long = 400, size: Long = 1000, sha: String = "a".repeat(64), available: Boolean = true) =
        AppUpdateDto(available, version, code, size, sha, "/v1/app/update/apk")

    @Test fun onlyANewerWellFormedOfferIsAccepted() {
        assertNotNull(AppUpdateInfo.from(dto(), installedCode = 300))
        assertNull(AppUpdateInfo.from(dto(), installedCode = 400), "same version")
        assertNull(AppUpdateInfo.from(dto(available = false), 300))
        assertNull(AppUpdateInfo.from(dto(version = "../../evil"), 300), "the version ends up in a file name")
        assertNull(AppUpdateInfo.from(dto(version = ""), 300))
        assertNull(AppUpdateInfo.from(dto(sha = "xyz"), 300))
        assertNull(AppUpdateInfo.from(dto(sha = "g".repeat(64)), 300))
        assertNull(AppUpdateInfo.from(dto(size = 0), 300))
        assertNull(AppUpdateInfo.from(dto(size = UpdatePolicy.MAX_APK_BYTES + 1), 300))
        assertEquals("a".repeat(64), AppUpdateInfo.from(dto(sha = "A".repeat(64)), 300)?.sha256)
    }

    @Test fun checkAndDismissPolicy() {
        assertTrue(UpdatePolicy.isCheckDue(0, 1_000))
        assertFalse(UpdatePolicy.isCheckDue(1_000, 2_000))
        assertTrue(UpdatePolicy.isCheckDue(1_000, 2_000, force = true))
        assertTrue(UpdatePolicy.isCheckDue(1_000, 1_000 + UpdatePolicy.CHECK_INTERVAL_MS))
        assertTrue(UpdatePolicy.isCheckDue(5_000, 1_000), "clock went backwards")
        assertTrue(UpdatePolicy.isDismissed(400, info))
        assertFalse(UpdatePolicy.isDismissed(399, info))
    }

    @Test fun downloadsVerifiesAndKeepsTheApk() = runBlocking<Unit> {
        serve()
        val progress = ArrayList<Long>()
        val outcome = downloader().download(server.baseUrl, info) { bytes, _ -> progress += bytes }
        val file = (outcome as DownloadOutcome.Success).file
        assertEquals("audiobooks-0.4.0.apk", file.name)
        assertEquals(apkBytes.size.toLong(), file.length())
        assertEquals(apkBytes.size.toLong(), progress.last())
        assertTrue(ApkVerifier.matches(file, info))
        assertNull(server.requests.single().headers["Authorization"], "the APK route is public")
        // A second call reuses the verified file without asking the server.
        downloader().download(server.baseUrl, info) { _, _ -> }
        assertEquals(1, server.requests.size)
    }

    @Test fun aChecksumMismatchDeletesTheFile() = runBlocking<Unit> {
        serve(ByteArray(apkBytes.size) { 1 })
        val outcome = downloader().download(server.baseUrl, info) { _, _ -> }
        assertEquals(DownloadOutcome.Failure(UpdateError.Corrupt), outcome)
        assertTrue(files.dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun aWrongLengthIsRefused() = runBlocking<Unit> {
        serve(apkBytes.copyOf(1000))
        assertEquals(DownloadOutcome.Failure(UpdateError.Corrupt), downloader().download(server.baseUrl, info) { _, _ -> })
    }

    @Test fun serverAndStorageErrors() = runBlocking<Unit> {
        server.on("GET", "/v1/app/update/apk", FakeServer.error(404, "not_found"))
        assertEquals(DownloadOutcome.Failure(UpdateError.NotAvailable), downloader().download(server.baseUrl, info) { _, _ -> })
        server.on("GET", "/v1/app/update/apk", MockResponse.Builder().code(500).build())
        assertEquals(DownloadOutcome.Failure(UpdateError.Server(500)), downloader().download(server.baseUrl, info) { _, _ -> })
        assertEquals(DownloadOutcome.Failure(UpdateError.InstallStorage), downloader(free = 1000).download(server.baseUrl, info) { _, _ -> })
        assertIs<DownloadOutcome.Failure>(downloader().download(server.baseUrl, info.copy(versionName = "../x")) { _, _ -> })
    }

    @Test fun oldVersionsAreCleanedUp() {
        files.ensureDir()
        files.apk("0.3.0").writeText("old")
        files.apk("0.4.0").writeText("keep")
        files.cleanupExcept("0.4.0")
        assertEquals(listOf("audiobooks-0.4.0.apk"), files.dir.list().orEmpty().toList())
        files.clearAll()
        assertTrue(files.dir.list().orEmpty().isEmpty())
    }

    @Test fun installStatusMapping() {
        assertEquals(InstallOutcome.NeedsUserAction, InstallStatus.map(InstallStatus.PENDING_USER_ACTION, null))
        assertEquals(InstallOutcome.Success, InstallStatus.map(InstallStatus.SUCCESS, null))
        assertEquals(InstallOutcome.Cancelled, InstallStatus.map(InstallStatus.FAILURE_ABORTED, null))
        assertEquals(InstallOutcome.Failed(UpdateError.SignatureMismatch), InstallStatus.map(InstallStatus.FAILURE, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match"))
        assertEquals(InstallOutcome.Failed(UpdateError.InstallStorage), InstallStatus.map(InstallStatus.FAILURE_STORAGE, null))
        assertTrue(InstallIntents.isTrustedConfirmIntent("android.content.pm.action.CONFIRM_INSTALL", "com.android.packageinstaller", 0, "xyz.aresius.audiobooks") { true })
        assertFalse(InstallIntents.isTrustedConfirmIntent("android.content.pm.action.CONFIRM_INSTALL", "com.evil", 0, "xyz.aresius.audiobooks") { false })
        assertFalse(InstallIntents.isTrustedConfirmIntent("android.intent.action.VIEW", "com.android.packageinstaller", 0, "xyz.aresius.audiobooks") { true })
    }
}
