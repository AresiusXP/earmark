package xyz.aresius.audiobooks.core.torrent

import android.app.NotificationManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The real engine from the app's Hilt graph with the real policy (the emulator has no VPN): a download that the policy pauses still
 * runs the foreground service, with the "connect your VPN" notification and Pause/Resume/Stop actions; the service goes away by itself
 * once nothing is collected any more. No network is used (the policy blocks before any torrent is added).
 */
class TorrentServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val notifications = context.getSystemService(NotificationManager::class.java)

    private fun ongoing() = notifications.activeNotifications.firstOrNull { it.id == TorrentNotifications.ONGOING_ID }

    @Test fun serviceRunsWhileADownloadIsCollectedAndStopsWhenIdle() = runBlocking<Unit> {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        val ep = EntryPointAccessors.fromApplication(context, TorrentEntryPoint::class.java)
        ep.settings().update { it.copy(p2pEnabled = true, requireVpn = true) }
        val hash = "2".repeat(40)
        val events = java.util.concurrent.CopyOnWriteArrayList<TorrentProgress>()
        val job = launch {
            ep.engine().download(TorrentDownload(hash, "magnet:?xt=urn:btih:$hash", listOf("a.mp3"), File(context.cacheDir, "svc-test"))).collect { events += it }
        }
        try {
            withTimeout(20_000) { while (events.lastOrNull() != TorrentProgress.Paused(PauseReason.VPN_REQUIRED)) delay(100) }
            withTimeout(20_000) { while (ongoing() == null) delay(100) }
            val n = ongoing()!!.notification
            assertEquals(context.getString(xyz.aresius.audiobooks.R.string.torrent_notif_paused_vpn), n.extras.getCharSequence("android.text").toString())
            assertEquals(2, n.actions.size, "Resume and Stop are offered while paused")
            assertEquals(PauseReason.VPN_REQUIRED, ep.runtime().activity.value.paused)
            assertEquals(1, ep.runtime().activity.value.downloads)
        } finally {
            job.cancel()
            job.join()
        }
        assertTrue(ep.runtime().activity.value.idle)
        withTimeout(30_000) { while (ongoing() != null) delay(200) }
    }

    /**
     * Android 15 dataSync time limit. Manual: `adb -s emulator-5600 shell device_config put activity_manager data_sync_fgs_timeout_duration 20000`
     * then run with `-Pandroid.testInstrumentationRunnerArguments.fgsTimeout=true`; the system calls onTimeout after ~20 s.
     */
    @Test fun dataSyncTimeoutPausesNotifiesAndSchedulesTheResumeJob() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("fgsTimeout") == "true")
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        val ep = EntryPointAccessors.fromApplication(context, TorrentEntryPoint::class.java)
        ep.settings().update { it.copy(p2pEnabled = true, requireVpn = true) }
        val hash = "3".repeat(40)
        val job = launch { ep.engine().download(TorrentDownload(hash, "magnet:?xt=urn:btih:$hash", listOf("a.mp3"), File(context.cacheDir, "svc-test"))).collect { } }
        try {
            withTimeout(120_000) { while (!ep.runtime().suspendedForTimeLimit) delay(250) }
            assertEquals(PauseReason.USER, ep.runtime().activity.value.paused)
            withTimeout(10_000) { while (notifications.activeNotifications.none { it.id == TorrentNotifications.TIMEOUT_ID }) delay(100) }
            val infos = androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("torrent-resume").get()
            assertTrue(infos.any { it.state == androidx.work.WorkInfo.State.ENQUEUED }, "resume job is scheduled: $infos")
            withTimeout(10_000) { while (ongoing() != null) delay(200) }
        } finally {
            job.cancel(); job.join()
            ep.runtime().resumeAfterTimeLimit()
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("torrent-resume")
        }
    }
}
