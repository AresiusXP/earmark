package xyz.aresius.audiobooks.core.download

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.auth.SessionStore

/** Called once from the Application: schedules the periodic housekeeping, brings every active job back to life after a restart, and follows the settings. */
@Singleton
class DownloadsStarter @Inject constructor(
    private val sessions: SessionStore,
    private val settings: DownloadSettings,
    private val maintenance: DownloadMaintenance,
    private val scheduler: DownloadScheduler,
    private val storage: StorageManager,
) {
    fun start(scope: CoroutineScope) {
        scheduler.ensureMaintenanceScheduled()
        scope.launch {
            sessions.session.map { it?.userId }.distinctUntilChanged().filterNotNull().collect { runCatching { maintenance.run() } }
        }
        scope.launch {
            settings.prefs.map { it.allowMobileData }.distinctUntilChanged().drop(1).collect { _ ->
                sessions.load()?.userId?.let { maintenance.rescheduleActive(it) }
            }
        }
        scope.launch {
            settings.prefs.map { it.storageLimitBytes to it.autoDeleteAfterDays }.distinctUntilChanged().drop(1).collect { (_, days) ->
                val user = sessions.load()?.userId ?: return@collect
                storage.autoDeleteFinished(user, days)
                storage.enforceLimit(user)
            }
        }
    }
}
