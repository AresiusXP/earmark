package xyz.aresius.audiobooks.core.torrent

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.FileStorage
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.settings_pack

/**
 * [TorrentEngine] on libtorrent4j. One [SessionManager] for the whole app, started on first use and stopped [EngineConfig.idleStopMs]
 * after the last torrent stopped. The safety policy ([TorrentPolicy]) is evaluated here, continuously, from the opt-in, the VPN /
 * network state and the battery: a blocked policy pauses the whole libtorrent session (all peer connections close), the DHT, LSD,
 * port mapping and incoming connections, so nothing leaves the phone outside the VPN until the policy allows again.
 *
 * Downloads: only the selected files get a priority above 0 (the others are never created; the pieces they share with a selected
 * file go to libtorrent's part file inside the destination). Files are fetched in the order of the request (priority 7, 6, 5, ...,
 * piece deadlines on the first incomplete file when several are selected). A file is reported complete only when all its pieces
 * passed the hash check. A single large M4B is therefore playable only when it is complete: the player plays complete files only.
 */
@Singleton
class LibTorrentEngine @Inject constructor(
    private val settingsSource: TorrentSettingsSource,
    private val monitor: DeviceStateMonitor,
    private val store: TorrentStore,
    private val launcher: ServiceLauncher,
    private val config: EngineConfig,
) : TorrentEngine, TorrentRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sm by lazy { SessionManager() }
    private val policy = TorrentPolicy()

    @Volatile private var settings = TorrentSettings()
    @Volatile private var device = monitor.state.value
    private val userPaused = MutableStateFlow(false)
    private val gate = MutableStateFlow<PolicyDecision?>(null)
    private val gateVersion = MutableStateFlow(0L)

    private val sessionLock = ReentrantLock()
    private val lifecycleMutex = Mutex()
    private var users = 0
    private var idleStop: Job? = null

    private val running = ConcurrentHashMap<String, Stat>()
    private val slots = LinkedHashSet<String>()
    private val seeding = ConcurrentHashMap<String, TorrentHandle>()
    private var seedJob: Job? = null
    /** Torrents whose fast-resume data is written to disk (the ones of [download]); a metadata fetch only hands its bytes to its caller. */
    private val persisting: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val fetchLocks = ConcurrentHashMap<String, Mutex>()
    private val resumeWaiters = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

    private val _activity = MutableStateFlow(EngineActivity())
    override val activity: StateFlow<EngineActivity> = _activity.asStateFlow()

    @Volatile override var suspendedForTimeLimit: Boolean = false
        private set

    private class Stat(var down: Long, var up: Long, var peers: Int, var done: Long, var total: Long)

    init {
        scope.launch {
            combine(settingsSource.settings, monitor.state, userPaused) { s, d, u -> Triple(s, d, u) }.collect { (s, d, u) ->
                settings = s
                device = d
                val decision = when {
                    !s.p2pEnabled -> PolicyDecision.Disabled
                    u -> PolicyDecision.Blocked(PauseReason.USER)
                    else -> policy.decide(s, d)
                }
                if (decision != gate.value) {
                    gate.value = decision
                    gateVersion.value++
                }
                sessionLock.withLock { applyGateLocked(s, decision) }
                publishActivity()
            }
        }
        if (config.observeProcessLifecycle) {
            Handler(Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) = resumeAfterTimeLimit()
                })
            }
        }
    }

    // ---- TorrentRuntime ----

    override fun setUserPaused(paused: Boolean) {
        userPaused.value = paused
        if (!paused) suspendedForTimeLimit = false
    }

    override fun stopAll() = setUserPaused(true)

    override fun suspendForTimeLimit() {
        suspendedForTimeLimit = true
        userPaused.value = true
    }

    override fun resumeAfterTimeLimit() {
        if (suspendedForTimeLimit) setUserPaused(false)
    }

    // ---- TorrentEngine ----

    override suspend fun fetchFiles(infoHash: String, magnet: String): List<TorrentFileInfo> {
        val hash = normalizeHash(infoHash)
        val params = parseMagnet(magnet, hash)
        // one fetch per torrent at a time: the second caller then finds the cached metadata
        return fetchLocks.getOrPut(hash) { Mutex() }.withLock {
            cachedInfo(hash)?.let { return@withLock filesOf(it) }
            when (val g = awaitGate()) {
                PolicyDecision.Allowed -> Unit
                else -> throw TorrentBlockedException(g)
            }
            usingSession {
                withContext(Dispatchers.IO) { fetchLocked(hash, params) }
            }
        }
    }

    private suspend fun fetchLocked(hash: String, params: AddTorrentParams): List<TorrentFileInfo> {
        val existing = sessionLock.withLock { sm.find(Sha1Hash.parseHex(hash)) }
        val handle = existing ?: sessionLock.withLock {
            val dir = store.fetchDir(hash).also { it.mkdirs() }
            params.setSavePath(dir.absolutePath)
            var flags = params.getFlags()
            flags = flags.and_(TorrentFlags.AUTO_MANAGED.inv()).or_(TorrentFlags.UPLOAD_MODE).or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
            params.setFlags(flags)
            val ec = error_code()
            val th = sm.swig().add_torrent(params.swig(), ec)
            if (ec.value() != 0) throw TorrentRejectedException("Could not add torrent: ${ec.message()}")
            TorrentHandle(th).also { it.resume() }
        }
        try {
            val deadline = System.currentTimeMillis() + config.metadataTimeoutMs
            var info: TorrentInfo? = null
            while (info == null) {
                currentCoroutineContext().ensureActive()
                val g = gate.value
                if (g != PolicyDecision.Allowed) throw TorrentBlockedException(g ?: PolicyDecision.Disabled)
                if (!handle.isValid()) throw TorrentRejectedException("Torrent was removed")
                if (handle.status(true).hasMetadata()) info = handle.torrentFile()
                if (info != null) break
                if (System.currentTimeMillis() >= deadline) throw java.util.concurrent.TimeoutException("No metadata within ${config.metadataTimeoutMs / 1000} s (no peers?)")
                delay(250)
            }
            val files = filesOf(info!!)
            store.saveMeta(hash, torrentFileBytes(info))
            return files
        } finally {
            if (existing == null) sessionLock.withLock { if (handle.isValid()) sm.remove(handle) }
            store.fetchDir(hash).deleteRecursively()
        }
    }

    override fun download(request: TorrentDownload): Flow<TorrentProgress> = flow {
        val hash = try {
            normalizeHash(request.infoHash)
        } catch (e: IllegalArgumentException) {
            emit(TorrentProgress.Failed(e.message ?: "Bad info hash")); return@flow
        }
        val selected = request.selectedPaths.distinct()
        if (selected.isEmpty()) { emit(TorrentProgress.Failed("No files selected")); return@flow }
        if (selected.any { TorrentPaths.resolveUnder(request.destination, it) == null }) {
            emit(TorrentProgress.Failed("A selected path is not safe")); return@flow
        }
        try {
            parseMagnet(request.magnet, hash)
        } catch (e: IllegalArgumentException) {
            emit(TorrentProgress.Failed(e.message ?: "Bad magnet")); return@flow
        }
        if (awaitGate() == PolicyDecision.Disabled) { emit(TorrentProgress.Failed("BitTorrent is turned off")); return@flow }
        if (running.putIfAbsent(hash, Stat(0, 0, 0, 0, 0)) != null) {
            emit(TorrentProgress.Failed("This torrent is already downloading")); return@flow
        }
        // A new request is an explicit user action: it clears a Stop / time-limit pause.
        if (userPaused.value) setUserPaused(false)
        var handle: TorrentHandle? = null
        var finished = false
        var used = false
        try {
            acquireUse(); used = true
            request.destination.mkdirs()
            store.saveDownload(hash, StoredDownload(request.magnet, request.destination.absolutePath, selected))
            publishActivity()

            val stall = StallDetector(config.stallNoPeersMs, config.stallNoProgressMs)
            var plan: Plan? = null
            var applied: IntArray? = null
            var paused = true
            var last: TorrentProgress? = null
            var lastResumeSave = System.currentTimeMillis()
            var lastCompleted: Set<Int> = emptySet()
            var spaceChecked = false
            suspend fun send(p: TorrentProgress) {
                if (p != last) { last = p; emit(p) }
            }

            while (true) {
                currentCoroutineContext().ensureActive()
                val seen = gateVersion.value
                when (val g = gate.value) {
                    PolicyDecision.Disabled, null -> { send(TorrentProgress.Failed("BitTorrent is turned off")); return@flow }
                    is PolicyDecision.Blocked -> {
                        handle?.takeIf { it.isValid() && !paused }?.let { it.pause(); paused = true }
                        releaseSlot(hash)
                        stall.reset()
                        send(TorrentProgress.Paused(g.reason))
                        waitTick(seen); continue
                    }
                    PolicyDecision.Allowed -> Unit
                }
                if (!takeSlot(hash)) { send(TorrentProgress.Starting(0)); waitTick(seen); continue }

                if (handle == null && !hasInfo(hash)) {
                    // Metadata first, in upload mode: adding a bare magnet would make libtorrent consider the torrent finished (no
                    // file is wanted yet) and drop the seeds before the selection is known.
                    val reason = stall.update(System.currentTimeMillis(), 0, 0)
                    send(if (reason == StallReason.NO_PEERS) TorrentProgress.Stalled(reason, 0) else TorrentProgress.Starting(0))
                    try {
                        withContext(Dispatchers.IO) { fetchLocked(hash, parseMagnet(request.magnet, hash)) }
                    } catch (e: TorrentBlockedException) {
                        waitTick(seen); continue
                    } catch (e: java.util.concurrent.TimeoutException) {
                        continue
                    } catch (e: TorrentRejectedException) {
                        send(TorrentProgress.Failed(e.message ?: "Rejected")); return@flow
                    }
                    stall.reset()
                }
                if (handle == null) {
                    val added = try {
                        withContext(Dispatchers.IO) { addForDownload(hash, request, selected, parseMagnet(request.magnet, hash)) }
                    } catch (e: TorrentRejectedException) {
                        send(TorrentProgress.Failed(e.message ?: "Rejected")); return@flow
                    } catch (e: Exception) {
                        send(TorrentProgress.Failed("Could not start the torrent: ${e.message}")); return@flow
                    }
                    handle = added.handle
                    persisting += hash
                    plan = added.plan
                    paused = true
                }
                val h = checkNotNull(handle)
                if (!h.isValid()) { send(TorrentProgress.Failed("The torrent was removed")); return@flow }
                val st = h.status(true)
                if (st.errorCode().isError) { send(TorrentProgress.Failed(st.errorCode().message)); return@flow }
                if (plan == null && st.hasMetadata()) {
                    val ti = h.torrentFile()
                    if (ti != null) {
                        plan = try { planFor(ti, selected, request.destination) } catch (e: TorrentRejectedException) {
                            send(TorrentProgress.Failed(e.message ?: "Rejected")); return@flow
                        }
                        applyPriorities(h, plan, emptySet())
                        applied = null
                    }
                }
                val state = st.state()
                val checking = state == TorrentStatus.State.CHECKING_FILES || state == TorrentStatus.State.CHECKING_RESUME_DATA
                if (plan == null || checking) {
                    if (paused) { h.resume(); paused = false }
                    running[hash]?.let { it.peers = st.numPeers(); it.down = 0; it.up = st.uploadPayloadRate().toLong() }
                    publishActivity()
                    val r = stall.update(System.currentTimeMillis(), st.numPeers(), 0)
                    send(if (r == StallReason.NO_PEERS && plan == null) TorrentProgress.Stalled(r, 0) else TorrentProgress.Starting(st.numPeers()))
                    waitTick(seen); continue
                }
                if (paused) { h.resume(); h.forceReannounce(); h.forceDHTAnnounce(); paused = false; stall.reset() }

                val fp = h.fileProgress(TorrentHandle.PIECE_GRANULARITY)
                val complete = plan.order.filter { fp.getOrElse(it) { 0L } >= plan.sizes[it] }.toSet()
                var done = 0L
                for (i in plan.order) done += minOf(fp.getOrElse(i) { 0L }, plan.sizes[i])
                if (!spaceChecked) {
                    spaceChecked = true
                    if (request.destination.usableSpace < plan.total - done) {
                        send(TorrentProgress.Failed("Not enough free storage for this download")); return@flow
                    }
                }
                if (complete.size == plan.order.size) {
                    withContext(NonCancellable + Dispatchers.IO) { finishTorrent(h, hash) }
                    handle = null
                    finished = true
                    send(TorrentProgress.Finished(plan.order.map { plan.pathOf.getValue(it) }.toSet()))
                    return@flow
                }
                if (applied == null || complete != lastCompleted) {
                    applied = applyPriorities(h, plan, complete)
                    lastCompleted = complete
                }
                if (plan.order.size > 1) setDeadlines(h, plan, complete)
                val rate = st.downloadPayloadRate().toLong()
                running[hash]?.let { it.down = rate; it.up = st.uploadPayloadRate().toLong(); it.peers = st.numPeers(); it.done = done; it.total = plan.total }
                publishActivity()
                val completedPaths = complete.map { plan.pathOf.getValue(it) }.toSet()
                val stalled = stall.update(System.currentTimeMillis(), st.numPeers(), done)
                send(
                    if (stalled != null) TorrentProgress.Stalled(stalled, done)
                    else TorrentProgress.Downloading(
                        bytesDone = done, bytesTotal = plan.total, downBytesPerSec = rate, peers = st.numPeers(), seeds = st.numSeeds(),
                        etaSeconds = if (rate > 0) (plan.total - done) / rate else null, completedPaths = completedPaths,
                    ),
                )
                if (System.currentTimeMillis() - lastResumeSave >= config.resumeSaveEveryMs) {
                    lastResumeSave = System.currentTimeMillis()
                    h.saveResumeData(RESUME_FLAGS)
                }
                waitTick(seen)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                val h = handle
                if (h != null && !finished) stopTorrent(h, hash)
                releaseSlot(hash)
                persisting -= hash
                running.remove(hash)
                publishActivity()
                if (used) releaseUse()
            }
        }
    }

    override suspend fun remove(infoHash: String, deleteFiles: Boolean) {
        val hash = normalizeHash(infoHash)
        withContext(Dispatchers.IO) {
            val record = store.loadDownload(hash)
            sessionLock.withLock {
                if (sm.isRunning) {
                    sm.find(Sha1Hash.parseHex(hash))?.let { h -> if (deleteFiles) sm.remove(h, SessionHandle.DELETE_FILES) else sm.remove(h) }
                }
            }
            if (seeding.remove(hash) != null) releaseUse()
            if (deleteFiles && record != null) deleteDownloaded(hash, record)
            store.forget(hash)
        }
    }

    internal val decision: PolicyDecision? get() = gate.value

    internal val isSessionPaused: Boolean get() = sm.isRunning && sm.isPaused

    internal fun isInSession(infoHash: String): Boolean = sm.isRunning && sm.find(Sha1Hash.parseHex(infoHash)) != null

    /** Stops the session and the engine's coroutines; for tests. */
    internal fun shutdown() {
        scope.cancel()
        sessionLock.withLock { stopSessionLocked() }
    }

    // ---- session lifecycle ----

    private suspend fun <T> usingSession(block: suspend () -> T): T {
        acquireUse()
        try {
            return block()
        } finally {
            withContext(NonCancellable) { releaseUse() }
        }
    }

    private suspend fun acquireUse() {
        lifecycleMutex.withLock {
            idleStop?.cancel(); idleStop = null
            users++
            if (!sm.isRunning) withContext(Dispatchers.IO) { sessionLock.withLock { startSessionLocked() } }
            if (users == 1) runCatching { launcher.ensureRunning() }
        }
    }

    private suspend fun releaseUse() {
        lifecycleMutex.withLock {
            users--
            if (users == 0) {
                idleStop = scope.launch {
                    delay(config.idleStopMs)
                    lifecycleMutex.withLock {
                        if (users == 0) withContext(Dispatchers.IO) { sessionLock.withLock { stopSessionLocked() } }
                    }
                }
            }
        }
    }

    private fun startSessionLocked() {
        if (sm.isRunning) return
        val params = store.sessionState.takeIf { it.isFile }
            ?.let { runCatching { SessionParams(it.readBytes()) }.getOrNull() } ?: SessionParams()
        val sp = params.getSettings()
        sp.listenInterfaces(config.listenInterfaces)
        sp.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), config.enablePortMaps)
        sp.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), config.enablePortMaps)
        sp.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), config.enableUtp)
        sp.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), config.enableUtp)
        // libtorrent waits 60 s before it reconnects to a peer it lost; a pause (VPN drop) loses every peer, so be quicker.
        sp.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 10)
        applyLimits(sp, settings)
        params.setSettings(sp)
        sm.addListener(alertListener)
        sm.start(params)
        applyGateLocked(settings, gate.value ?: PolicyDecision.Blocked(PauseReason.WIFI_REQUIRED))
    }

    private fun stopSessionLocked() {
        if (!sm.isRunning) return
        runCatching { sm.saveState()?.let { store.sessionState.writeBytes(it) } }
        sm.removeListener(alertListener)
        sm.stop()
    }

    private fun applyLimits(sp: SettingsPack, s: TorrentSettings) {
        sp.connectionsLimit(s.maxConnections)
        sp.activeDownloads(s.maxActiveTorrents)
        sp.activeSeeds(s.maxActiveTorrents)
        sp.activeLimit(s.maxActiveTorrents * 2)
        sp.downloadRateLimit(s.downloadLimitKib * 1024)
        sp.uploadRateLimit(s.uploadLimitKib * 1024)
    }

    /** Pauses the whole libtorrent session at once when the policy blocks; DHT, LSD, port mapping and incoming connections follow it. */
    private fun applyGateLocked(s: TorrentSettings, decision: PolicyDecision) {
        if (!sm.isRunning) return
        val allowed = decision == PolicyDecision.Allowed
        if (!allowed) sm.pause()
        val sp = sm.settings()
        applyLimits(sp, s)
        sp.setEnableDht(allowed && config.enableDht)
        sp.setEnableLsd(allowed && config.enableLsd)
        sp.setBoolean(settings_pack.bool_types.enable_incoming_tcp.swigValue(), allowed)
        sp.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), allowed && config.enableUtp)
        sp.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), allowed && config.enablePortMaps)
        sp.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), allowed && config.enablePortMaps)
        sm.applySettings(sp)
        if (allowed) sm.resume()
    }

    private suspend fun awaitGate(): PolicyDecision = gate.filterNotNull().first()

    private suspend fun waitTick(seenVersion: Long) {
        withTimeoutOrNull(config.tickMs) { gateVersion.first { it != seenVersion } }
    }

    // ---- download helpers ----

    private class Plan(
        val order: List<Int>,
        val pathOf: Map<Int, String>,
        val sizes: LongArray,
        val firstPiece: IntArray,
        val lastPiece: IntArray,
        val numFiles: Int,
    ) {
        val total: Long = order.sumOf { sizes[it] }
    }

    private class Added(val handle: TorrentHandle, val plan: Plan?)

    private fun addForDownload(hash: String, request: TorrentDownload, selected: List<String>, magnetParams: AddTorrentParams): Added {
        val resumed = store.loadResume(hash)?.let { parseResume(it) }?.takeIf { it.getInfoHashes().getBest().toHex() == hash }
        val p = resumed ?: cachedInfo(hash)?.let { info ->
            // the magnet only lends its trackers and peer hints: its (v1) info hash can differ from a hybrid torrent's
            AddTorrentParams().also {
                it.setTorrentInfo(info)
                it.setTrackers(magnetParams.getTrackers())
                it.setTrackerTiers(magnetParams.getTrackerTiers())
                it.peers(magnetParams.peers())
            }
        } ?: magnetParams
        if (resumed != null) {
            // fast-resume data does not carry the magnet's own peer hints (x.pe) or trackers
            p.peers((p.peers() + magnetParams.peers()).distinctBy { it.toString() })
            if (p.getTrackers().isEmpty()) p.setTrackers(magnetParams.getTrackers())
        }
        p.setSavePath(request.destination.absolutePath)
        var flags = p.getFlags()
        flags = flags.and_(
            TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.UPLOAD_MODE).or_(TorrentFlags.STOP_WHEN_READY).or_(TorrentFlags.SEED_MODE)
                .or_(TorrentFlags.SUPER_SEEDING).or_(TorrentFlags.DUPLICATE_IS_ERROR).inv(),
        ).or_(TorrentFlags.PAUSED)
        val ti = p.getTorrentInfo()
        val plan = ti?.let { planFor(it, selected, request.destination) }
        if (plan != null) {
            p.filePriorities(priorityArray(plan, emptySet()))
        } else {
            flags = flags.or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
        }
        p.setFlags(flags)
        val handle = sessionLock.withLock {
            val ec = error_code()
            val th = sm.swig().add_torrent(p.swig(), ec)
            if (ec.value() != 0) throw TorrentRejectedException("Could not add torrent: ${ec.message()}")
            TorrentHandle(th)
        }
        return Added(handle, plan)
    }

    private fun planFor(ti: TorrentInfo, selected: List<String>, destination: File): Plan {
        val files = filesOf(ti)
        val byPath = files.associateBy { it.path }
        val order = selected.map { path ->
            val f = byPath[path] ?: throw TorrentRejectedException("File not in this torrent: $path")
            if (TorrentPaths.resolveUnder(destination, f.path) == null) throw TorrentRejectedException("Unsafe path: $path")
            f.index
        }
        val fs = ti.files()
        val n = fs.numFiles()
        val sizes = LongArray(n) { fs.fileSize(it) }
        val first = IntArray(n) { if (sizes[it] > 0) fs.pieceIndexAtFile(it) else 0 }
        val last = IntArray(n) { if (sizes[it] > 0) fs.lastPieceIndexAtFile(it) else -1 }
        return Plan(order, order.associateWith { i -> files.first { it.index == i }.path }, sizes, first, last, n)
    }

    private fun priorityArray(plan: Plan, complete: Set<Int>): Array<Priority> =
        FilePlanner.priorities(plan.numFiles, plan.order, complete).map { Priority.fromSwig(it) }.toTypedArray()

    private fun applyPriorities(h: TorrentHandle, plan: Plan, complete: Set<Int>): IntArray {
        val arr = priorityArray(plan, complete)
        h.prioritizeFiles(arr)
        return IntArray(arr.size) { arr[it].swig().toInt() }
    }

    private fun setDeadlines(h: TorrentHandle, plan: Plan, complete: Set<Int>) {
        val first = plan.order.firstOrNull { it !in complete } ?: return
        val pieces = FilePlanner.deadlinePieces(plan.firstPiece[first], plan.lastPiece[first], { h.havePiece(it) })
        pieces.forEachIndexed { k, piece -> h.setPieceDeadline(piece, 500 + k * 250) }
    }

    private suspend fun finishTorrent(h: TorrentHandle, hash: String) {
        if (!h.isValid()) return
        h.flushCache()
        saveResume(h, hash)
        if (policy.seedingAllowed(settings, device)) {
            seeding[hash] = h
            lifecycleMutex.withLock { idleStop?.cancel(); idleStop = null; users++ }
            h.resume()
            startSeedJob()
        } else {
            sessionLock.withLock { sm.remove(h) }
        }
    }

    private suspend fun stopTorrent(h: TorrentHandle, hash: String) {
        if (!h.isValid()) return
        h.pause()
        saveResume(h, hash, 3_000)
        sessionLock.withLock { if (h.isValid() && sm.isRunning) sm.remove(h) }
    }

    private fun startSeedJob() {
        if (seedJob?.isActive == true) return
        seedJob = scope.launch {
            while (seeding.isNotEmpty()) {
                delay(5_000)
                val allowed = gate.value == PolicyDecision.Allowed && policy.seedingAllowed(settings, device)
                for ((hash, h) in seeding.entries.toList()) {
                    val done = !h.isValid() || h.status(true).let { it.allTimeUpload() >= maxOf(1L, it.totalWantedDone()) }
                    if (!allowed || done) {
                        sessionLock.withLock { if (h.isValid() && sm.isRunning) sm.remove(h) }
                        if (seeding.remove(hash) != null) releaseUse()
                    }
                }
                publishActivity()
            }
        }
    }

    private fun deleteDownloaded(hash: String, record: StoredDownload) {
        val dest = File(record.destination)
        for (path in record.selectedPaths) {
            val f = TorrentPaths.resolveUnder(dest, path) ?: continue
            f.delete()
            var dir = f.parentFile
            val root = dest.canonicalFile
            while (dir != null && dir != root && dir.path.startsWith(root.path) && dir.list()?.isEmpty() == true) {
                dir.delete()
                dir = dir.parentFile
            }
        }
        File(dest, ".$hash.parts").delete()
    }

    private fun publishActivity() {
        var down = 0L; var up = 0L; var peers = 0; var done = 0L; var total = 0L
        for (s in running.values) { down += s.down; up += s.up; peers += s.peers; done += s.done; total += s.total }
        val g = gate.value
        _activity.value = EngineActivity(
            downloads = running.size, downBytesPerSec = down, upBytesPerSec = up, peers = peers, bytesDone = done, bytesTotal = total,
            etaSeconds = if (down > 0 && total > done) (total - done) / down else null,
            paused = (g as? PolicyDecision.Blocked)?.reason,
            seeding = seeding.size,
        )
    }

    private fun takeSlot(hash: String): Boolean = synchronized(slots) {
        slots.contains(hash) || (slots.size < settings.maxActiveTorrents && slots.add(hash))
    }

    private fun releaseSlot(hash: String) = synchronized(slots) { slots.remove(hash) }

    // ---- resume data ----

    private val alertListener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(AlertType.SAVE_RESUME_DATA.swig(), AlertType.SAVE_RESUME_DATA_FAILED.swig())

        override fun alert(alert: Alert<*>) {
            val hash = runCatching { (alert as TorrentAlert<*>).handle().infoHash().toHex() }.getOrNull() ?: return
            val bytes = if (alert.type() == AlertType.SAVE_RESUME_DATA) {
                runCatching { AddTorrentParams.writeResumeDataBuf((alert as SaveResumeDataAlert).params()) }.getOrNull()
            } else null
            if (bytes != null && persisting.contains(hash)) runCatching { store.saveResume(hash, bytes) }
            resumeWaiters.remove(hash)?.complete(bytes)
        }
    }

    private suspend fun saveResume(h: TorrentHandle, hash: String, timeoutMs: Long = 5_000): ByteArray? {
        if (!h.isValid()) return null
        val waiter = CompletableDeferred<ByteArray?>()
        resumeWaiters.put(hash, waiter)?.complete(null)
        h.saveResumeData(RESUME_FLAGS)
        val bytes = withTimeoutOrNull(timeoutMs) { waiter.await() }
        resumeWaiters.remove(hash, waiter)
        return bytes
    }

    private fun hasInfo(hash: String): Boolean = cachedInfo(hash) != null || store.loadResume(hash) != null

    private fun cachedInfo(hash: String): TorrentInfo? =
        store.loadMeta(hash)?.let { runCatching { TorrentInfo(it) }.getOrNull() }?.takeIf { it.infoHash().toHex() == hash }

    private fun parseResume(bytes: ByteArray): AddTorrentParams? {
        val ec = error_code()
        val p = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(bytes), ec)
        return if (ec.value() != 0) null else AddTorrentParams(p)
    }

    private fun parseMagnet(magnet: String, expectedHash: String): AddTorrentParams {
        val p = try {
            AddTorrentParams.parseMagnetUri(magnet)
        } catch (e: IllegalArgumentException) {
            throw TorrentRejectedException("Not a valid magnet link")
        }
        if (p.getInfoHashes().getBest().toHex() != expectedHash) throw TorrentRejectedException("The magnet link does not match the info hash")
        return p
    }

    companion object {
        private val RESUME_FLAGS = TorrentHandle.SAVE_INFO_DICT.or_(TorrentHandle.FLUSH_DISK_CACHE)

        fun normalizeHash(infoHash: String): String {
            val h = infoHash.lowercase()
            require(h.length == 40 && h.all { it in '0'..'9' || it in 'a'..'f' }) { "Info hash must be 40 hex characters" }
            return h
        }

        /**
         * A minimal .torrent made of the raw info dictionary (so the info hash is unchanged). Hybrid v1+v2 torrents fetched by magnet
         * have no piece layers, which libtorrent refuses to write out; the raw info section always works.
         */
        internal fun torrentFileBytes(ti: TorrentInfo): ByteArray {
            val span = ti.swig().get_info_section()
            val info = ByteArray(span.size().toInt()) { span.at(it.toLong()) }
            return "d4:info".toByteArray() + info + "e".toByteArray()
        }

        /** The files of a torrent in metainfo order, without BEP 47 pad files; rejects the whole torrent when any path is unsafe or a symlink. */
        fun filesOf(ti: TorrentInfo): List<TorrentFileInfo> {
            val fs: FileStorage = ti.files()
            val out = ArrayList<TorrentFileInfo>()
            for (i in 0 until fs.numFiles()) {
                if (fs.fileFlags(i).and_(FileStorage.FLAG_SYMLINK).non_zero()) throw TorrentRejectedException("Torrent contains a symlink")
                if (fs.padFileAt(i)) continue
                val path = TorrentPaths.safeRelative(fs.filePath(i)) ?: throw TorrentRejectedException("Torrent contains an unsafe path")
                out += TorrentFileInfo(i, path, fs.fileSize(i))
            }
            return out
        }
    }
}
