package xyz.aresius.audiobooks.core.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.aresius.audiobooks.core.auth.BuildInfo
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.data.AppPreferences
import xyz.aresius.audiobooks.core.data.AppUpdateRepository
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.HttpClientFactory
import xyz.aresius.audiobooks.core.network.SignedOutEvents

fun interface UpdateDownloads {
    suspend fun download(update: AppUpdateInfo, onProgress: (bytes: Long, total: Long) -> Unit): DownloadOutcome
}

interface UpdateEnvironment {
    val installedVersionName: String
    val installedVersionCode: Long
    fun isOnline(): Boolean
    fun now(): Long
}

@Singleton
class AndroidUpdateEnvironment @Inject constructor(
    @ApplicationContext private val context: Context,
    buildInfo: BuildInfo,
) : UpdateEnvironment {
    override val installedVersionName: String = buildInfo.versionName
    override val installedVersionCode: Long = buildInfo.versionCode

    override fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun now(): Long = System.currentTimeMillis()
}

/** The server's APK through the app's plain client (the route is public), without the API call timeout. */
@Singleton
class ServerUpdateDownloads @Inject constructor(
    private val prefs: AppPreferences,
    private val clients: HttpClientFactory,
    private val files: UpdateFiles,
) : UpdateDownloads {
    private val downloader by lazy {
        ApkDownloader(clients.plain().newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build(), files, freeSpace = { dir ->
            val target = generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() } ?: dir
            StatFs(target.path).availableBytes
        })
    }

    override suspend fun download(update: AppUpdateInfo, onProgress: (Long, Long) -> Unit): DownloadOutcome =
        downloader.download(prefs.serverUrl.first(), update, onProgress)
}

/**
 * Application-scoped update state machine: check when the app comes to the foreground (throttled to 10 min), dismissal, download
 * (own scope, so it survives screen changes), install. The UI only observes [state] and calls the methods below.
 */
@Singleton
class UpdateManager(
    private val repo: AppUpdateRepository,
    private val prefs: AppPreferences,
    private val downloads: UpdateDownloads,
    private val installer: ApkInstaller,
    private val files: UpdateFiles,
    private val env: UpdateEnvironment,
    private val sessions: SessionStore,
    private val signedOut: SignedOutEvents,
    private val results: InstallResults,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        repo: AppUpdateRepository,
        prefs: AppPreferences,
        downloads: UpdateDownloads,
        installer: ApkInstaller,
        files: UpdateFiles,
        env: UpdateEnvironment,
        sessions: SessionStore,
        signedOut: SignedOutEvents,
        results: InstallResults,
    ) : this(repo, prefs, downloads, installer, files, env, sessions, signedOut, results, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val _state = MutableStateFlow(UpdateState(installedVersionName = env.installedVersionName, installedVersionCode = env.installedVersionCode))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val checkLock = Mutex()
    private var downloadJob: Job? = null
    private var lastCheckMs = 0L
    private var started = false

    fun start() {
        if (started) return
        started = true
        results.listener = ::onInstallOutcome
        scope.launch { signedOut.events.collect { reset() } }
    }

    /** The app came to the foreground. */
    fun onForeground() {
        scope.launch { check(force = false) }
    }

    /** "Check now": ignores the throttle. */
    fun checkNow() {
        scope.launch { check(force = true) }
    }

    fun dismiss() {
        val update = _state.value.available ?: return
        _state.update { it.copy(dismissed = true) }
        scope.launch { prefs.setDismissedUpdateVersionCode(maxOf(prefs.dismissedUpdateVersionCode.first(), update.versionCode)) }
    }

    /** Downloads the offered APK and, when [thenInstall], hands it to the installer. */
    fun download(thenInstall: Boolean = true) {
        val update = _state.value.available ?: return
        if (downloadJob?.isActive == true) return
        when (_state.value.phase) {
            UpdatePhase.Installing, is UpdatePhase.Downloading -> return
            UpdatePhase.Ready -> {
                if (thenInstall) install()
                return
            }
            else -> Unit
        }
        downloadJob = scope.launch {
            setPhase(UpdatePhase.Downloading(0, update.size))
            val outcome = try {
                downloads.download(update) { bytes, total -> setPhase(UpdatePhase.Downloading(bytes, total)) }
            } catch (e: CancellationException) {
                setPhase(UpdatePhase.Idle)
                throw e
            }
            when (outcome) {
                is DownloadOutcome.Success -> {
                    setPhase(UpdatePhase.Ready)
                    if (thenInstall) install()
                }
                is DownloadOutcome.Failure -> setPhase(UpdatePhase.Failed(outcome.error))
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Verifies the cached APK again and installs it; asks for "Install unknown apps" first when it is off. */
    fun install() {
        val update = _state.value.available ?: return
        if (!claimInstall()) return
        scope.launch {
            try {
                val apk = files.apk(update.versionName)
                val intact = withContext(io) { ApkVerifier.matches(apk, update) }
                if (!intact) {
                    withContext(io) { files.deleteVersion(update.versionName) }
                    setPhase(UpdatePhase.Failed(UpdateError.Corrupt))
                } else if (!installer.canInstall()) {
                    setPhase(UpdatePhase.NeedsInstallPermission())
                } else {
                    when (val start = installer.install(apk)) {
                        is InstallStart.Session -> Unit
                        is InstallStart.Failed -> setPhase(UpdatePhase.Failed(start.error))
                    }
                }
            } catch (e: CancellationException) {
                setPhase(UpdatePhase.Ready)
                throw e
            }
        }
    }

    /** True for exactly one of any number of concurrent callers: moves a startable phase to [UpdatePhase.Installing]. */
    private fun claimInstall(): Boolean {
        while (true) {
            val current = _state.value
            if (current.available == null || current.phase.isBusy) return false
            if (_state.compareAndSet(current, current.copy(phase = UpdatePhase.Installing))) return true
        }
    }

    fun unknownSourcesSettingsOpened() {
        _state.update { if (it.phase is UpdatePhase.NeedsInstallPermission) it.copy(phase = UpdatePhase.NeedsInstallPermission(settingsOpened = true)) else it }
    }

    fun cancelInstallPermissionPrompt() {
        _state.update { if (it.phase is UpdatePhase.NeedsInstallPermission) it.copy(phase = UpdatePhase.Ready) else it }
    }

    /** Activity resumed: continues the install when the user came back from the unknown-sources screen. */
    fun onResumed() {
        val phase = _state.value.phase
        if (phase is UpdatePhase.NeedsInstallPermission && phase.settingsOpened) {
            if (installer.canInstall()) install() else setPhase(UpdatePhase.Ready)
        }
    }

    private suspend fun check(force: Boolean) {
        if (!checkLock.tryLock()) return
        try {
            if (sessions.load() == null || _state.value.phase.isBusy) return
            val now = env.now()
            if (!UpdatePolicy.isCheckDue(lastCheckMs, now, force)) return
            if (!force && !env.isOnline()) return
            _state.update { it.copy(checking = true) }
            val r = withTimeoutOrNull(CHECK_BUDGET_MS) { repo.check(env.installedVersionCode) }
            when (r) {
                is ApiResult.Success -> {
                    lastCheckMs = now
                    apply(r.value, prefs.dismissedUpdateVersionCode.first())
                    _state.update { it.copy(checking = false, lastCheckedAtMs = now) }
                }
                else -> _state.update { it.copy(checking = false) } // silent: the next foreground visit tries again
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(checking = false) }
            throw e
        } finally {
            checkLock.unlock()
        }
    }

    private suspend fun apply(offer: AppUpdateInfo?, dismissedCode: Long) {
        withContext(io) { files.cleanupExcept(offer?.versionName) }
        val intact = offer != null && withContext(io) {
            files.apk(offer.versionName).let { it.exists() && ApkVerifier.matches(it, offer).also { ok -> if (!ok) it.delete() } }
        }
        _state.update {
            val phase = when {
                it.phase.isBusy -> it.phase
                intact -> UpdatePhase.Ready
                else -> UpdatePhase.Idle
            }
            it.copy(available = offer, dismissed = offer?.let { o -> UpdatePolicy.isDismissed(dismissedCode, o) } ?: false, phase = phase)
        }
    }

    private suspend fun reset() {
        downloadJob?.cancel()
        results.clear()
        withContext(io) { files.clearAll() }
        lastCheckMs = 0
        _state.update { UpdateState(installedVersionName = env.installedVersionName, installedVersionCode = env.installedVersionCode) }
    }

    private fun onInstallOutcome(outcome: InstallOutcome) {
        if (_state.value.phase !is UpdatePhase.Installing) return
        when (outcome) {
            InstallOutcome.NeedsUserAction -> Unit
            InstallOutcome.Success -> scope.launch {
                withContext(io) { files.clearAll() }
                setPhase(UpdatePhase.Idle)
            }
            InstallOutcome.Cancelled -> setPhase(UpdatePhase.Ready)
            is InstallOutcome.Failed -> {
                if (outcome.error == UpdateError.SignatureMismatch) {
                    _state.value.available?.let { u -> scope.launch { withContext(io) { files.deleteVersion(u.versionName) } } }
                }
                setPhase(UpdatePhase.Failed(outcome.error))
            }
        }
    }

    private fun setPhase(phase: UpdatePhase) = _state.update { it.copy(phase = phase) }

    private companion object {
        const val CHECK_BUDGET_MS = 50_000L
    }
}

/** Wires the manager to the app: called once from `AudiobooksApp.onCreate`. */
@Singleton
class UpdateStarter @Inject constructor(private val manager: UpdateManager) {
    fun start() {
        manager.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = manager.onForeground()
        })
    }
}
