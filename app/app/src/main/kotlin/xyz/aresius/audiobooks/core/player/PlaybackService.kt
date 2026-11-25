package xyz.aresius.audiobooks.core.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.data.LibraryDao
import xyz.aresius.audiobooks.core.data.ReleaseRepository
import xyz.aresius.audiobooks.core.download.DownloadsRepository
import xyz.aresius.audiobooks.core.data.cacheLocalProgress
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Book

/**
 * Owns the ExoPlayer and the media session: foreground media notification, lock-screen controls, headset buttons, audio focus,
 * becoming-noisy, and a wake + Wi-Fi lock while playing (so a book keeps playing with the screen off). All the book logic is in
 * [PlaybackCore]; the UI talks to it through [PlaybackController] and reads [PlayerHub]. The session's player is a [BookPlayer]
 * (chapters as the playlist), so car and watch controllers get chapters and the configured skip length.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var dataSources: PlaybackDataSources
    @Inject lateinit var hub: PlayerHub
    @Inject lateinit var loader: BookLoader
    @Inject lateinit var sync: ProgressSync
    @Inject lateinit var releases: ReleaseRepository
    @Inject lateinit var downloads: DownloadsRepository
    @Inject lateinit var prefs: PlayerPreferences
    @Inject lateinit var libraryDao: LibraryDao
    @Inject lateinit var sessions: SessionStore
    @Inject lateinit var scopes: PlayerScopes
    @Inject lateinit var durationReporter: DurationReporter

    private var session: MediaSession? = null
    private var core: PlaybackCore? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val mediaSourceFactory = DefaultMediaSourceFactory(this, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true))
            .setDataSourceFactory(dataSources.factory())
            .setLoadErrorHandlingPolicy(PlaybackLoadErrorPolicy())
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 2_500, 5_000).build())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.preloadConfiguration = ExoPlayer.PreloadConfiguration(PRELOAD_NEXT_US)
        val bookPlayer = BookPlayer(exo)
        val playbackCore = PlaybackCore(
            exo = exo,
            bookPlayer = bookPlayer,
            scope = scope,
            io = scopes.io,
            hub = hub,
            loader = loader,
            sync = sync,
            releases = releases,
            prefs = prefs,
            shake = AccelerometerShakeSource(this),
            libraryCache = { userId, entry -> libraryDao.cacheLocalProgress(userId, entry, System.currentTimeMillis()) },
            chapterTitle = { c -> c.title.ifBlank { getString(R.string.player_chapter_n, c.index + 1) } },
            elapsed = SystemClock::elapsedRealtime,
            durationReports = durationReporter,
            onStarted = { plan ->
                if (plan.files.any { it.local == null }) scopes.io.launch { downloads.autoDownloadIfEnabled(plan.book, plan.release, plan.provider?.wire) }
            },
        )
        core = playbackCore

        val openApp = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, bookPlayer)
            .setCallback(SessionCallback(playbackCore))
            .setSessionActivity(openApp)
            .setBitmapLoader(CoilBitmapLoader(this, scope))
            .build()
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                Log.w(TAG, "foreground service start not allowed (background start); playback continues, the notification is posted when allowed")
            }
        })
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).setChannelName(R.string.player_notification_channel).build()
                .also { it.setSmallIcon(R.drawable.ic_notification) },
        )
        scope.launch { combine(prefs.skipBackSeconds, prefs.skipForwardSeconds, ::Pair).collect { (back, fwd) -> session?.setMediaButtonPreferences(skipButtons(back, fwd)) } }
        // An account that signs out (or another one signing in) ends playback of the previous user's book.
        scope.launch {
            var previous: String? = null
            sessions.session.map { it?.userId }.distinctUntilChanged().collect { current ->
                if (previous != null && current != previous) playbackCore.close()
                previous = current
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away while paused: nothing to keep alive. A playing book goes on (foreground service). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        core?.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun skipButtons(back: Int, forward: Int): List<CommandButton> = listOf(
        CommandButton.Builder(skipIcon(back, forwardIcon = false)).setPlayerCommand(Player.COMMAND_SEEK_BACK).setDisplayName(getString(R.string.player_skip_back_n, back)).build(),
        CommandButton.Builder(skipIcon(forward, forwardIcon = true)).setPlayerCommand(Player.COMMAND_SEEK_FORWARD).setDisplayName(getString(R.string.player_skip_forward_n, forward)).build(),
    )

    private fun skipIcon(seconds: Int, forwardIcon: Boolean): Int = when (seconds) {
        10 -> if (forwardIcon) CommandButton.ICON_SKIP_FORWARD_10 else CommandButton.ICON_SKIP_BACK_10
        15 -> if (forwardIcon) CommandButton.ICON_SKIP_FORWARD_15 else CommandButton.ICON_SKIP_BACK_15
        30 -> if (forwardIcon) CommandButton.ICON_SKIP_FORWARD_30 else CommandButton.ICON_SKIP_BACK_30
        else -> if (forwardIcon) CommandButton.ICON_SKIP_FORWARD else CommandButton.ICON_SKIP_BACK
    }

    private class SessionCallback(private val core: PlaybackCore) : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply { PlayerCommands.ALL.forEach(::add) }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder()
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                PlayerCommands.LOAD -> {
                    val book = args.getString(PlayerCommands.KEY_BOOK_JSON)?.let { runCatching { AudiobooksJson.decodeFromString(Book.serializer(), it) }.getOrNull() }
                    core.load(
                        asin = args.getString(PlayerCommands.KEY_ASIN).orEmpty(),
                        releaseHash = args.getString(PlayerCommands.KEY_RELEASE),
                        hint = book,
                        autoplay = args.getBoolean(PlayerCommands.KEY_AUTOPLAY, true),
                    )
                }
                PlayerCommands.SEEK_BOOK -> core.seekToBook(args.getLong(PlayerCommands.KEY_POSITION_MS))
                PlayerCommands.SET_SPEED -> core.setSpeed(args.getFloat(PlayerCommands.KEY_SPEED, 1f))
                PlayerCommands.SKIP_BACK -> session.player.seekBack()
                PlayerCommands.SKIP_FORWARD -> session.player.seekForward()
                PlayerCommands.SLEEP_START -> {
                    val minutes = args.getInt(PlayerCommands.KEY_MINUTES, PlayerCommands.END_OF_CHAPTER)
                    core.startSleep(if (minutes == PlayerCommands.END_OF_CHAPTER) SleepSetting.EndOfChapter else SleepSetting.Minutes(minutes))
                }
                PlayerCommands.SLEEP_CANCEL -> core.cancelSleep()
                PlayerCommands.SLEEP_EXTEND -> core.extendSleep()
                PlayerCommands.RETRY -> core.retry()
                PlayerCommands.CLOSE -> core.close()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private companion object {
        const val TAG = "AudiobooksPlayer"
        const val PRELOAD_NEXT_US = 10_000_000L
    }
}
