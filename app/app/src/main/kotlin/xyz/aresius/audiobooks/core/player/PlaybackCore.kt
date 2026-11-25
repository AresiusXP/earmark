package xyz.aresius.audiobooks.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.data.ReleaseRepository
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.ErrorCode
import xyz.aresius.audiobooks.core.network.LibraryEntry
import xyz.aresius.audiobooks.core.network.LibraryStatus
import xyz.aresius.audiobooks.core.network.Prepare

/** Detects the phone being shaken (to extend the sleep timer); the Android sensor implementation lives with the service. */
interface ShakeSource {
    fun start(onShake: () -> Unit)
    fun stop()
}

/**
 * The brain of playback, one per service: loads a book into the ExoPlayer, keeps the [PlayerHub] current, runs the sleep timer, smart
 * rewind, progress persistence and sync, and turns provider "preparing" and errors into states. Everything runs on the main thread (the
 * player's application looper); persistence goes through [io] so a write outlives the service.
 */
class PlaybackCore(
    private val exo: ExoPlayer,
    private val bookPlayer: BookPlayer,
    private val scope: CoroutineScope,
    private val io: CoroutineScope,
    private val hub: PlayerHub,
    private val loader: BookLoader,
    private val sync: ProgressSync,
    private val releases: ReleaseRepository,
    private val prefs: PlayerPreferences,
    private val shake: ShakeSource,
    private val libraryCache: suspend (userId: String, entry: LibraryEntry) -> Unit,
    private val chapterTitle: (BookChapter) -> String,
    private val elapsed: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleepTimer: SleepTimerLogic = SleepTimerLogic(elapsed),
) {
    private var plan: PlayPlan? = null
    private var timeline: BookTimeline? = null
    private var chapters: ChapterMap? = null
    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var prepareJob: Job? = null
    private var lastChapter = -1
    private var pausedAt: Long? = null
    private var lastPersist = 0L
    private var lastCacheWrite = 0L
    private var loadCounter = 0L
    private var skipBackMs = PlaybackRules.DEFAULT_SKIP_S * 1000L
    private var skipForwardMs = PlaybackRules.DEFAULT_SKIP_S * 1000L
    private var smartRewindOn = true
    private var speed = 1f

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (plan == null) return
            if (isPlaying) ensureTicker() else persist(force = true)
            publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (plan == null) return
            if (playbackState == Player.STATE_ENDED) onEnded()
            publish()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (plan == null) return
            if (!playWhenReady) {
                if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) pausedAt = elapsed()
                return
            }
            if (exo.playbackState == Player.STATE_ENDED) {
                exo.seekTo(0, 0)
                hub.update { copy(ended = false) }
            } else {
                pausedAt?.let { since -> if (smartRewindOn) rewindFor(elapsed() - since) }
            }
            pausedAt = null
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = learnDurations(timeline)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publish()

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = publish()

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (plan == null) return
            val next = PlaybackRules.clampSpeed(playbackParameters.speed)
            if (next == speed) return
            speed = next
            persist(force = false)
            publish()
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    init {
        exo.addListener(listener)
        scope.launch { prefs.skipBackSeconds.collect { skipBackMs = it * 1000L; reapplyView() } }
        scope.launch { prefs.skipForwardSeconds.collect { skipForwardMs = it * 1000L; reapplyView() } }
        scope.launch { prefs.smartRewind.collect { smartRewindOn = it } }
    }

    // --- loading -----------------------------------------------------------------------------------------------

    /** Opens [asin] (the release the user chose, else the remembered/best one) and starts it. The same book already loaded just plays. */
    fun load(asin: String, releaseHash: String?, hint: Book?, autoplay: Boolean = true) {
        val current = plan
        if (current != null && current.book.asin == asin && (releaseHash == null || releaseHash == current.release.infoHash) && hub.state.value.phase == PlayerPhase.Ready) {
            if (autoplay) exo.play()
            return
        }
        closeCurrent()
        loadCounter++
        hub.update { PlayerState(phase = PlayerPhase.Loading, asin = asin, book = hint, loadId = loadCounter) }
        loadJob = scope.launch {
            when (val r = loader.load(asin, releaseHash)) {
                is LoadResult.Failure -> hub.update { copy(phase = PlayerPhase.Failed(r.kind, r.message)) }
                is LoadResult.Ready -> start(r.plan, autoplay)
            }
        }
    }

    private suspend fun start(first: PlayPlan, autoplay: Boolean) {
        hub.update { copy(book = first.book, releaseHash = first.release.infoHash, provider = first.provider) }
        var p = first
        if (p.needsStream || p.files.isEmpty()) {
            p = awaitReady(p) ?: return
        }
        if (p.files.isEmpty()) return hub.update { copy(phase = PlayerPhase.Failed(FailureKind.NoReleases)) }
        if (p.files.any { !it.playable(p.provider) }) return hub.update { copy(phase = PlayerPhase.Failed(FailureKind.NeedsSource)) }
        loader.remember(p.userId, p.book.asin, p.release, p.provider)

        val tl = BookTimeline(p.files.map { BookFile(it.path, it.index, it.sizeBytes, it.durationMs) }, p.book.runtimeMin * 60_000L)
        plan = p
        timeline = tl
        chapters = ChapterMap.build(tl, p.chapters)
        lastChapter = -1
        speed = PlaybackRules.clampSpeed((p.resume?.speed ?: 1.0).toFloat())
        skipBackMs = prefs.skipBackSeconds.first() * 1000L
        skipForwardMs = prefs.skipForwardSeconds.first() * 1000L
        smartRewindOn = prefs.smartRewind.first()

        var startMs = resumeBookMs(p.resume, tl)
        if (smartRewindOn && p.resume?.status == LibraryStatus.LISTENING && p.resume.updatedAt > 0) {
            startMs = (startMs - PlaybackRules.smartRewindMs(clock() - p.resume.updatedAt)).coerceAtLeast(0)
        }
        val at = tl.toFile(startMs)
        val items = p.files.mapIndexed { i, f ->
            val uri = f.local?.let { Uri.fromFile(it) } ?: StreamUri.of(p.release.infoHash, requireNotNull(f.index), requireNotNull(p.provider))
            MediaItem.Builder().setMediaId("${p.book.asin}:$i").setUri(uri).build()
        }
        exo.setMediaItems(items, at.fileIndex, at.offsetMs)
        exo.playbackParameters = PlaybackParameters(speed, 1f)
        exo.volume = 1f
        exo.prepare()
        exo.playWhenReady = autoplay
        pausedAt = if (autoplay) null else elapsed()
        reapplyView()
        hub.update { copy(phase = PlayerPhase.Ready, fileCount = p.files.size) }
        publish()
        persist(force = false)
        io.launch { sync.flush(p.userId) }
        if (autoplay) ensureTicker()
    }

    private fun resumeBookMs(resume: ResumePoint?, tl: BookTimeline): Long {
        if (resume == null || resume.status == LibraryStatus.FINISHED) return 0
        val i = resume.filePath?.let(tl::indexOfPath) ?: -1
        val ms = if (i >= 0) tl.toBook(i, resume.filePositionMs) else resume.positionMs
        return ms.coerceIn(0, tl.totalMs)
    }

    /** Asks the provider to prepare the release until it is ready (visible as "preparing"); null after publishing a failure. */
    private suspend fun awaitReady(p: PlayPlan): PlayPlan? {
        val provider = p.provider ?: run {
            hub.update { copy(phase = PlayerPhase.Failed(FailureKind.NeedsSource)) }
            return null
        }
        while (scope.isActive) {
            when (val r = releases.prepare(p.release.infoHash, provider, p.release.magnet.ifBlank { null })) {
                is ApiResult.Success -> when (r.value.state) {
                    Prepare.READY -> return if (p.files.isEmpty()) loader.withProviderFiles(p, r.value.files) else p
                    Prepare.FAILED -> {
                        hub.update { copy(phase = PlayerPhase.Failed(FailureKind.Provider, r.value.reason)) }
                        return null
                    }
                    else -> {
                        hub.update { copy(phase = PlayerPhase.Preparing(r.value.progress, r.value.reason)) }
                        delay(((r.value.retryAfterS ?: DEFAULT_POLL_S).coerceIn(2, 15)) * 1000L)
                    }
                }
                is ApiResult.Failure -> {
                    hub.update { copy(phase = PlayerPhase.Failed(r.error.toFailureKind(), (r.error as? ApiError.Http)?.message)) }
                    return null
                }
            }
        }
        return null
    }

    private fun ApiError.toFailureKind(): FailureKind = when {
        this is ApiError.Http && code == ErrorCode.NoDebridKey -> FailureKind.NoDebridKey
        this is ApiError.Http && code == ErrorCode.BadDebridKey -> FailureKind.BadDebridKey
        this is ApiError.Http && status == 429 -> FailureKind.RateLimited
        this is ApiError.Http && status == 401 || this == ApiError.NotSignedIn -> FailureKind.NotSignedIn
        this == ApiError.Network -> FailureKind.Network
        this is ApiError.Http -> FailureKind.Provider
        else -> FailureKind.Unknown
    }

    private fun handleError(error: PlaybackException) {
        val p = plan ?: return
        persist(force = true)
        val stream = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<StreamException>().firstOrNull()
        when (stream) {
            is StreamException.Preparing -> {
                hub.update { copy(phase = PlayerPhase.Preparing(stream.prepare.progress, stream.prepare.reason)) }
                prepareJob?.cancel()
                prepareJob = scope.launch { if (awaitReady(p) != null) retry() }
            }
            is StreamException.NeedsKey -> fail(if (stream.badKey) FailureKind.BadDebridKey else FailureKind.NoDebridKey, stream.serverMessage)
            is StreamException.Unauthorized, is StreamException.NotSignedIn -> fail(FailureKind.NotSignedIn)
            is StreamException.RateLimited -> fail(FailureKind.RateLimited)
            is StreamException.NotFound, is StreamException.Failed -> fail(FailureKind.Provider, stream.serverMessage)
            null -> fail(FailureKind.Stream)
        }
    }

    private fun fail(kind: FailureKind, message: String? = null) {
        hub.update { copy(phase = PlayerPhase.Failed(kind, message), playing = false, buffering = false) }
    }

    /** After a failure: tries again from the position where it stopped. */
    fun retry() {
        val p = plan
        if (p == null) {
            hub.state.value.asin?.let { load(it, hub.state.value.releaseHash, hub.state.value.book) }
            return
        }
        hub.update { copy(phase = PlayerPhase.Ready) }
        exo.prepare()
        exo.play()
        ensureTicker()
    }

    // --- durations, chapters, state ----------------------------------------------------------------------------

    private fun learnDurations(source: Timeline) {
        val p = plan ?: return
        var tl = timeline ?: return
        val window = Timeline.Window()
        for (i in 0 until minOf(source.windowCount, tl.files.size)) {
            source.getWindow(i, window)
            if (window.isDynamic || window.durationUs == C.TIME_UNSET) continue
            val ms = window.durationUs / 1000
            if (tl.files[i].durationMs == ms) continue
            tl = tl.withDuration(i, ms)
            val path = tl.files[i].path
            io.launch { loader.learnDuration(p.release.infoHash, path, ms) }
        }
        if (tl !== timeline) {
            timeline = tl
            chapters = ChapterMap.build(tl, p.chapters)
            lastChapter = -1
            reapplyView()
            publish()
        }
    }

    private fun reapplyView() {
        val p = plan ?: return
        val tl = timeline ?: return
        val cm = chapters ?: return
        bookPlayer.view = BookView(p.book, tl, cm, skipBackMs, skipForwardMs, chapterTitle)
        hub.update { copy(skipBackMs = this@PlaybackCore.skipBackMs, skipForwardMs = this@PlaybackCore.skipForwardMs) }
    }

    private fun publish() {
        val tl = timeline ?: return
        val cm = chapters ?: return
        val pos = bookPlayer.bookPositionMs()
        val ci = cm.indexAt(pos)
        if (ci != lastChapter) {
            lastChapter = ci
            bookPlayer.refresh()
        }
        val state = exo.playbackState
        // Named locals: inside the lambda `speed` and `sleep` would be the PlayerState's own properties.
        val currentSpeed = speed
        val sleepNow = sleepTimer.state(pos, currentSpeed)
        hub.update {
            copy(
                positionMs = pos, durationMs = tl.totalMs, durationExact = tl.exact, chapters = cm.chapters, chapterSource = cm.source, chapterIndex = ci,
                speed = currentSpeed, sleep = sleepNow, playing = exo.isPlaying, buffering = exo.playWhenReady && state == Player.STATE_BUFFERING,
                ended = state == Player.STATE_ENDED,
            )
        }
    }

    // --- ticker: UI position, sleep timer, persistence -----------------------------------------------------------

    private fun ensureTicker() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                val fading = (sleepTimer.state(bookPlayer.bookPositionMs(), speed) as? SleepState.Running)?.fading == true
                delay(if (fading) FAST_TICK_MS else TICK_MS)
                onTick()
                if (!exo.isPlaying && !sleepTimer.isRunning) break
            }
        }
    }

    private fun onTick() {
        val cm = chapters ?: return
        val pos = bookPlayer.bookPositionMs()
        if (sleepTimer.isRunning) {
            val t = sleepTimer.tick(pos, speed) { at -> cm.at(at)?.endMs }
            exo.volume = t.volume
            if (t.fire) {
                exo.pause()
                exo.volume = 1f
                shake.stop()
            }
        }
        publish()
        if (exo.isPlaying && elapsed() - lastPersist >= PERSIST_EVERY_MS) persist(force = false)
    }

    private fun persist(force: Boolean) {
        val p = plan ?: return
        val tl = timeline ?: return
        if (exo.mediaItemCount == 0) return
        val fileIndex = exo.currentMediaItemIndex.coerceIn(0, tl.files.lastIndex)
        val offset = exo.currentPosition.coerceAtLeast(0)
        val pos = tl.toBook(fileIndex, offset)
        val ended = exo.playbackState == Player.STATE_ENDED
        val finished = ended || (fileIndex == tl.files.lastIndex && PlaybackRules.isFinished(pos, tl.totalMs))
        val point = ResumePoint(
            status = if (finished) LibraryStatus.FINISHED else LibraryStatus.LISTENING,
            releaseHash = p.release.infoHash,
            positionMs = if (ended) tl.totalMs else pos,
            filePath = tl.files[fileIndex].path,
            filePositionMs = if (ended) tl.durationOf(fileIndex) else offset,
            speed = speed.toDouble(),
            updatedAt = 0,
        )
        lastPersist = elapsed()
        val cacheNow = force || elapsed() - lastCacheWrite >= SYNC_EVERY_MS
        if (cacheNow) lastCacheWrite = elapsed()
        val userId = p.userId
        val book = p.book
        io.launch {
            sync.record(userId, book.asin, point, force)
            if (cacheNow) libraryCache(userId, LibraryEntry(book, point.status, point.releaseHash, point.positionMs, point.filePath, point.filePositionMs, point.speed, clock()))
        }
    }

    private fun onEnded() {
        persist(force = true)
        sleepTimer.cancel()
        shake.stop()
        exo.volume = 1f
    }

    private fun rewindFor(pausedMs: Long) {
        val back = PlaybackRules.smartRewindMs(pausedMs)
        if (back <= 0) return
        bookPlayer.seekToBook((bookPlayer.bookPositionMs() - back).coerceAtLeast(0))
    }

    // --- commands ----------------------------------------------------------------------------------------------

    fun setSpeed(value: Float) {
        exo.playbackParameters = PlaybackParameters(PlaybackRules.clampSpeed(value), 1f)
    }

    fun startSleep(setting: SleepSetting) {
        val cm = chapters ?: return
        val pos = bookPlayer.bookPositionMs()
        sleepTimer.start(setting, pos, cm.at(pos)?.endMs)
        shake.start { extendSleep() }
        ensureTicker()
        publish()
    }

    fun cancelSleep() {
        sleepTimer.cancel()
        shake.stop()
        exo.volume = 1f
        publish()
    }

    fun extendSleep() {
        val cm = chapters ?: return
        val pos = bookPlayer.bookPositionMs()
        sleepTimer.extend(cm.chapters.getOrNull(cm.indexAt(pos) + 1)?.endMs ?: cm.at(pos)?.endMs)
        exo.volume = 1f
        publish()
    }

    fun seekToBook(ms: Long) {
        bookPlayer.seekToBook(ms)
        publish()
    }

    /** Dismisses the player: saves where it stands, stops, and forgets the book (the notification goes away). */
    fun close() {
        closeCurrent()
        hub.reset()
    }

    private fun closeCurrent() {
        loadJob?.cancel()
        prepareJob?.cancel()
        persist(force = true)
        tickJob?.cancel()
        sleepTimer.cancel()
        shake.stop()
        exo.stop()
        exo.clearMediaItems()
        exo.volume = 1f
        bookPlayer.view = null
        plan = null
        timeline = null
        chapters = null
        pausedAt = null
    }

    fun release() {
        persist(force = true)
        loadJob?.cancel()
        prepareJob?.cancel()
        tickJob?.cancel()
        shake.stop()
        exo.removeListener(listener)
    }

    private companion object {
        const val TICK_MS = 500L
        const val FAST_TICK_MS = 200L
        const val PERSIST_EVERY_MS = 5_000L
        const val SYNC_EVERY_MS = 30_000L
        const val DEFAULT_POLL_S = 3
    }
}
