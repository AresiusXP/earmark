package xyz.aresius.audiobooks.feature.player

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.AudiobooksTheme
import xyz.aresius.audiobooks.core.design.CoverImage
import xyz.aresius.audiobooks.core.design.CoverShape
import xyz.aresius.audiobooks.core.design.PrimaryButton
import xyz.aresius.audiobooks.core.design.SecondaryButton
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.design.TabularFigures
import xyz.aresius.audiobooks.core.design.WavyProgressBar
import xyz.aresius.audiobooks.core.design.coverColorScheme
import xyz.aresius.audiobooks.core.design.rememberCoverColors
import xyz.aresius.audiobooks.core.player.PlayerPhase
import xyz.aresius.audiobooks.core.player.PlayerState
import xyz.aresius.audiobooks.core.player.SleepState
import xyz.aresius.audiobooks.feature.common.authorLine

private enum class Sheet { Chapters, Speed, Sleep, Bookmarks }

/**
 * The full-screen player: always dark, its background and accents taken from the cover. Big cover, serif title, small-caps author, the chapter
 * line, a wavy seek bar over the WHOLE book with a tick at every chapter start, time elapsed and left, squircle skip buttons around a round
 * play button, and pills for speed, sleep timer and bookmarks. While the book opens, the provider prepares it, or something failed, the
 * controls give way to that state in words.
 */
@Composable
fun PlayerScreen(onBack: () -> Unit, onOpenBook: (String) -> Unit, onOpenSettings: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    AudiobooksTheme(darkTheme = true) {
        val cover by rememberCoverColors(state.book?.coverUrl)
        val scheme = coverColorScheme(MaterialTheme.colorScheme, cover)
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            LightStatusBarIcons(false)
            AskForNotificationPermission()
            LaunchedEffect(state.hasBook) { if (!state.hasBook) onBack() }
            // The mini player restored after a restart opens here: the book is loaded now (paused), not before the user asks for it.
            LaunchedEffect(state.restored) { if (state.restored) viewModel.prepareRestored() }
            // The player is always dark: Text and Icon without a colour must follow its scheme, not the (light) app theme around it.
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                PlayerContent(state, bookmarks, viewModel, glow = cover?.glow, onBack, onOpenBook, onOpenSettings)
            }
        }
    }
}

@Composable
private fun PlayerContent(
    state: PlayerState,
    bookmarks: List<xyz.aresius.audiobooks.core.player.PlayerBookmark>,
    viewModel: PlayerViewModel,
    glow: Color?,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var menu by remember { mutableStateOf(false) }
    val book = state.book
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).drawBehind {
            if (glow != null) {
                val center = Offset(size.width / 2, size.height * 0.28f)
                drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.30f), Color.Transparent), center = center, radius = 300.dp.toPx()), radius = 300.dp.toPx(), center = center)
            }
        },
    ) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
            Box(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.player_open_book)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (book != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.player_open_book)) }, onClick = { menu = false; onOpenBook(book.asin) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.player_close)) }, onClick = { menu = false; viewModel.close() })
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Box(Modifier.fillMaxWidth(0.72f).aspectRatio(1f)) {
                CoverImage(book?.coverUrl, Modifier.fillMaxSize(), shape = CoverShape, contentDescription = book?.title, shadow = true)
            }
            Text(book?.title.orEmpty(), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val authors = book?.authorLine().orEmpty()
            if (authors.isNotEmpty()) {
                Text(authors.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            when (val phase = state.phase) {
                PlayerPhase.Idle, PlayerPhase.Loading -> LoadingBlock()
                is PlayerPhase.Preparing -> PreparingBlock(phase)
                is PlayerPhase.Failed -> FailedBlock(phase, onRetry = viewModel::retry, onAddKey = onOpenSettings, onClose = viewModel::close)
                PlayerPhase.Ready -> ReadyBlock(state, viewModel, bookmarkCount = bookmarks.size, onSheet = { sheet = it })
            }
        }
    }
    when (sheet) {
        Sheet.Chapters -> ChaptersSheet(state, onPick = { viewModel.seekTo(it.startMs); sheet = null }, onDismiss = { sheet = null })
        Sheet.Speed -> SpeedSheet(state.speed, onSpeed = viewModel::setSpeed, onDismiss = { sheet = null })
        Sheet.Sleep -> SleepSheet(
            state.sleep,
            onStart = { setting ->
                viewModel.startSleep(if (setting is xyz.aresius.audiobooks.core.player.SleepSetting.Minutes) setting.minutes else xyz.aresius.audiobooks.core.player.PlayerCommands.END_OF_CHAPTER)
            },
            onExtend = viewModel::extendSleep,
            onCancel = viewModel::cancelSleep,
            onDismiss = { sheet = null },
        )
        Sheet.Bookmarks -> BookmarksSheet(
            bookmarks,
            positionMs = state.positionMs,
            onAdd = viewModel::addBookmark,
            onJump = { viewModel.seekTo(it.positionMs); sheet = null },
            onDelete = { viewModel.deleteBookmark(it.id) },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

@Composable
private fun ReadyBlock(state: PlayerState, viewModel: PlayerViewModel, bookmarkCount: Int, onSheet: (Sheet) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        val chapter = state.chapter
        if (chapter != null) {
            Column(Modifier.clickable { onSheet(Sheet.Chapters) }.padding(horizontal = Spacing.s, vertical = Spacing.xs), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(chapter.displayTitle(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.player_chapter_times, formatClock(state.chapterPositionMs), formatClock(state.chapterDurationMs)),
                        style = MaterialTheme.typography.labelMedium.merge(TabularFigures),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(Icons.Rounded.ExpandMore, contentDescription = stringResource(R.string.player_chapters), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
        Scrubber(state, onSeek = viewModel::seekTo)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = viewModel::previousChapter) { Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.player_previous_chapter), modifier = Modifier.size(32.dp)) }
            SkipButton(seconds = (state.skipBackMs / 1000).toInt(), forward = false, onClick = viewModel::skipBack)
            PlayPauseButton(playing = state.playing, busy = state.buffering, onClick = viewModel::togglePlay)
            SkipButton(seconds = (state.skipForwardMs / 1000).toInt(), forward = true, onClick = viewModel::skipForward)
            IconButton(onClick = viewModel::nextChapter) { Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.player_next_chapter), modifier = Modifier.size(32.dp)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally)) {
            PlayerPill(stringResource(R.string.player_speed_value, formatSpeed(state.speed)), onClick = { onSheet(Sheet.Speed) }, highlighted = state.speed != 1f)
            val sleep = state.sleep
            PlayerPill(
                if (sleep is SleepState.Running) formatClock(sleep.remainingMs) else stringResource(R.string.player_sleep),
                onClick = { onSheet(Sheet.Sleep) },
                icon = Icons.Rounded.Bedtime,
                highlighted = sleep is SleepState.Running,
            )
            PlayerPill(
                stringResource(R.string.player_bookmark),
                onClick = { onSheet(Sheet.Bookmarks) },
                icon = if (bookmarkCount > 0) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
            )
        }
    }
}

/** The whole-book seek bar: dragging shows the target at once and the player is asked once, when the finger lifts. */
@Composable
private fun Scrubber(state: PlayerState, onSeek: (Long) -> Unit) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    var committed by remember { mutableStateOf<Long?>(null) }
    val duration by rememberUpdatedState(state.durationMs)
    val seek by rememberUpdatedState(onSeek)
    // Stable lambdas: the bar restarts its gesture handling when they change, which would cancel a drag every half second.
    val onScrub = remember { { fraction: Float -> scrub = fraction } }
    val onScrubEnd: () -> Unit = remember {
        {
            val f = scrub
            if (f != null) {
                val target = (f * duration).toLong()
                committed = target
                seek(target)
            }
        }
    }
    LaunchedEffect(committed) {
        if (committed != null) {
            delay(1_200)
            scrub = null
            committed = null
        }
    }
    LaunchedEffect(state.positionMs) {
        val target = committed
        if (target != null && kotlin.math.abs(state.positionMs - target) < 2_000) {
            scrub = null
            committed = null
        }
    }
    val total = state.durationMs.coerceAtLeast(1)
    val fraction = scrub ?: (state.positionMs.toFloat() / total)
    val shown = (fraction * total).toLong().coerceIn(0, total)
    val ticks = remember(state.chapters, state.durationMs) {
        if (state.chapters.size in 2..MAX_TICKS) state.chapters.drop(1).map { it.startMs.toFloat() / total } else null
    }
    val described = stringResource(R.string.player_chapter_times, formatClock(shown), formatClock(total))
    Column(Modifier.fillMaxWidth()) {
        WavyProgressBar(
            modifier = Modifier.semantics { stateDescription = described },
            progress = fraction,
            playing = state.playing && scrub == null,
            restingAmplitude = 0.35f,
            onSeek = onScrub,
            onSeekEnd = onScrubEnd,
            tickFractions = ticks,
            height = 40.dp,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatClock(shown), style = MaterialTheme.typography.labelLarge.merge(TabularFigures), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatRemaining(total - shown, !state.durationExact), style = MaterialTheme.typography.labelLarge.merge(TabularFigures), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val MAX_TICKS = 80

@Composable
private fun LoadingBlock() {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.player_loading), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PreparingBlock(phase: PlayerPhase.Preparing) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.l), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(R.string.player_preparing_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(stringResource(R.string.player_preparing_message), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        WavyProgressBar(progress = (phase.progress ?: 0) / 100f, playing = true, showThumb = false)
        phase.progress?.let { Text(stringResource(R.string.player_preparing_progress, it), style = MaterialTheme.typography.labelLarge.merge(TabularFigures), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        phase.reasonText()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun FailedBlock(phase: PlayerPhase.Failed, onRetry: () -> Unit, onAddKey: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(R.string.player_fail_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(phase.text(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Column(Modifier.padding(top = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            when (phase.kind.action()) {
                FailureAction.Retry -> PrimaryButton(stringResource(R.string.common_retry), onClick = onRetry)
                FailureAction.AddKey -> {
                    PrimaryButton(stringResource(R.string.player_action_add_key), onClick = onAddKey)
                    SecondaryButton(stringResource(R.string.common_retry), onClick = onRetry)
                }
                FailureAction.SignIn, FailureAction.None -> Unit
            }
            SecondaryButton(stringResource(R.string.player_close), onClick = onClose)
        }
    }
}

/** The status-bar icons stay light on the dark player and go back to the system's choice when it closes. */
@Composable
private fun LightStatusBarIcons(light: Boolean) {
    val view = LocalView.current
    DisposableEffect(light) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = light
        onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
    }
}

/** Android 13+: the playback notification needs this permission; asked once, when the player opens. Playback works without it. */
@Composable
private fun AskForNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
