package xyz.aresius.audiobooks.feature.book

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.Badge
import xyz.aresius.audiobooks.core.design.BadgeStyle
import xyz.aresius.audiobooks.core.design.CardShape
import xyz.aresius.audiobooks.core.design.CoverImage
import xyz.aresius.audiobooks.core.design.CoverShape
import xyz.aresius.audiobooks.core.design.DetailTopBar
import xyz.aresius.audiobooks.core.design.ErrorState
import xyz.aresius.audiobooks.core.design.LoadingShimmer
import xyz.aresius.audiobooks.core.design.PrimaryButton
import xyz.aresius.audiobooks.core.design.SecondaryButton
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.design.coverColorScheme
import xyz.aresius.audiobooks.core.design.coverShadow
import xyz.aresius.audiobooks.core.design.rememberCoverColors
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.Chapters
import xyz.aresius.audiobooks.core.network.ReleaseView
import xyz.aresius.audiobooks.core.player.PlayerPhase
import xyz.aresius.audiobooks.feature.player.formatClock
import xyz.aresius.audiobooks.feature.player.text
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.feature.download.DownloadButton
import xyz.aresius.audiobooks.feature.download.DownloadControlsViewModel
import xyz.aresius.audiobooks.feature.download.DownloadStatus
import xyz.aresius.audiobooks.feature.download.ReleaseDownloadButton
import xyz.aresius.audiobooks.feature.common.authorLine
import xyz.aresius.audiobooks.feature.common.formatInstant
import xyz.aresius.audiobooks.feature.common.formatRuntime
import xyz.aresius.audiobooks.feature.common.formatSize
import xyz.aresius.audiobooks.feature.common.message
import xyz.aresius.audiobooks.feature.common.seriesLine

/**
 * The book page. Its background and tonal accents come from the cover (androidx.palette, darkened and desaturated toward the warm ink so
 * text keeps AA contrast), cross-faded when the cover arrives; without a cover color the base scheme shows.
 */
@Composable
fun BookScreen(onBack: () -> Unit, onPlay: () -> Unit, onOpenSettings: () -> Unit, viewModel: BookViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResumed()
        onPauseOrDispose { }
    }
    val coverUrl = (state.detail as? Load.Content)?.value?.book?.coverUrl
    val cover by rememberCoverColors(coverUrl)
    val scheme = coverColorScheme(MaterialTheme.colorScheme, cover)
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        BookContent(state, onBack, onPlay, onOpenSettings, viewModel, glow = cover?.glow)
    }
}

@Composable
private fun BookContent(state: BookUiState, onBack: () -> Unit, onPlay: () -> Unit, onOpenSettings: () -> Unit, viewModel: BookViewModel, glow: Color?) {
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.message()
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText)
            viewModel.consumeError()
        }
    }
    val dark = isSystemInDarkTheme()
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(
            if (dark && glow != null) {
                // Dark theme: a soft glow of the cover's swatch behind the cover (light theme: the cover's shadow instead).
                Modifier.drawBehind {
                    val center = Offset(size.width / 2, 150.dp.toPx() + 112.dp.toPx())
                    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.30f), Color.Transparent), center = center, radius = 260.dp.toPx()), radius = 260.dp.toPx(), center = center)
                }
            } else {
                Modifier
            },
        ),
    ) {
        val detail = state.detail
        DetailTopBar(
            title = "",
            onBack = onBack,
            actions = {
                if (detail is Load.Content) {
                    IconButton(onClick = { viewModel.load() }, enabled = !state.refreshing) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.book_refresh_releases))
                    }
                }
            },
        )
        Column(Modifier.weight(1f)) {
            when (detail) {
                Load.Loading -> BookLoading()
                is Load.Failed -> ErrorState(detail.error.message(), onRetry = { viewModel.load() })
                is Load.Content -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    item {
                        Hero(
                            detail.value.book, detail.value.releases, state,
                            onToggleLibrary = viewModel::toggleLibrary,
                            onPlay = { viewModel.play(); onPlay() },
                            onOpenSettings = onOpenSettings,
                        )
                    }
                    detail.value.book.description?.takeIf { it.isNotBlank() }?.let { description ->
                        item { Text(description, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Spacing.screen)) }
                    }
                    item {
                        ReleasesHeader(count = detail.value.releases.size, searchedAt = detail.value.searchedAt, stale = detail.stale, refreshing = state.refreshing)
                    }
                    if (detail.value.releases.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.book_no_releases),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Spacing.screen),
                            )
                        }
                    }
                    items(detail.value.releases, key = { it.infoHash }) { release ->
                        ReleaseCard(
                            release,
                            book = detail.value.book,
                            chosen = state.chosen?.release?.infoHash == release.infoHash,
                            onChoose = { viewModel.choose(release) },
                            modifier = Modifier.padding(horizontal = Spacing.screen),
                        )
                    }
                    chaptersSection(state.chapters)
                    item { Box(Modifier.height(Spacing.l)) }
                }
            }
        }
        SnackbarHost(snackbar)
    }
}

@Composable
private fun BookLoading() {
    Column(Modifier.fillMaxWidth().padding(Spacing.screen), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        LoadingShimmer(Modifier.size(220.dp))
        LoadingShimmer(Modifier.fillMaxWidth(0.7f).height(34.dp), RoundedCornerShape(8.dp))
        LoadingShimmer(Modifier.fillMaxWidth(0.4f).height(16.dp), RoundedCornerShape(6.dp))
    }
}

/** One strong hero: a big cover (soft glow in the dark theme, soft shadow in the light one), the serif title, then the facts. */
@Composable
private fun Hero(book: Book, releases: List<ReleaseView>, state: BookUiState, onToggleLibrary: () -> Unit, onPlay: () -> Unit, onOpenSettings: () -> Unit) {
    val downloads: DownloadControlsViewModel = hiltViewModel()
    val downloadRelease = state.chosen?.release?.takeIf { downloads.canDownload(it) } ?: downloads.bestRelease(releases)
    val dark = isSystemInDarkTheme()
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Box(Modifier.size(224.dp).then(if (dark) Modifier else Modifier.coverShadow(CoverShape))) {
            CoverImage(book.coverUrl, Modifier.size(224.dp), shape = CoverShape, contentDescription = book.title, asin = book.asin)
        }
        Text(book.title, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        book.subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (book.authors.isNotEmpty()) Text(book.authorLine().uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (book.narrators.isNotEmpty()) {
            Text(stringResource(R.string.book_narrated_by) + " " + book.narrators.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        book.seriesLine()?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        val facts = listOfNotNull(formatRuntime(book.runtimeMin), book.releaseYear?.toString(), book.language.takeIf { it.isNotBlank() })
        Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium.merge(xyz.aresius.audiobooks.core.design.TabularFigures), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (book.genres.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                book.genres.forEach { Badge(it) }
            }
        }
        when (state.availability) {
            is PlayAvailability.Playable -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    val resume = (state.libraryEntry?.positionMs ?: 0) > 0
                    PrimaryButton(stringResource(if (resume) R.string.book_play_resume else R.string.book_play), onClick = onPlay, modifier = Modifier.weight(1f))
                    DownloadButton(book, downloadRelease, modifier = Modifier.weight(1f))
                }
                state.playerPhase?.let { PlayerStatusLine(it) }
            }
            PlayAvailability.NeedsKey -> {
                NeedsKeyBlock(onOpenSettings)
                DownloadButton(book, downloadRelease, modifier = Modifier.fillMaxWidth())
            }
            PlayAvailability.NoReleases -> Unit
        }
        DownloadStatus(book.asin)
        SecondaryButton(
            text = stringResource(if (state.libraryEntry != null) R.string.book_remove_from_library else R.string.book_add_to_library),
            onClick = onToggleLibrary,
            enabled = !state.libraryBusy,
        )
    }
}

/** Streaming needs a debrid key and there are no downloads yet: say it and offer Settings (the Download button next to it may still work over BitTorrent). */
@Composable
private fun NeedsKeyBlock(onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(R.string.book_needs_key_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.book_needs_key_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton(stringResource(R.string.book_open_settings), onClick = onOpenSettings)
    }
}

/** What the player is doing with this book when it is not simply playing: opening, being prepared by the provider, or failed. */
@Composable
private fun PlayerStatusLine(phase: PlayerPhase) {
    val text = when (phase) {
        PlayerPhase.Idle, PlayerPhase.Ready -> return
        PlayerPhase.Loading -> stringResource(R.string.player_loading)
        is PlayerPhase.Preparing -> listOfNotNull(stringResource(R.string.player_preparing_title), phase.progress?.let { stringResource(R.string.player_preparing_progress, it) }).joinToString(" · ")
        is PlayerPhase.Failed -> phase.text()
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun ReleasesHeader(count: Int, searchedAt: String?, stale: Boolean, refreshing: Boolean) {
    Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HorizontalDivider(Modifier.padding(vertical = Spacing.s), color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(R.string.book_releases_title, count), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f, fill = false))
            if (refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        val searched = formatInstant(searchedAt)
        if (searched.isNotEmpty()) {
            Text(stringResource(R.string.book_searched_at, searched), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (stale) Text(stringResource(R.string.common_offline_copy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReleaseCard(release: ReleaseView, book: Book, chosen: Boolean, onChoose: () -> Unit, modifier: Modifier = Modifier) {
    var showFiles by remember(release.infoHash) { mutableStateOf(false) }
    val files = release.files.ifEmpty { release.torrentFiles }
    Card(modifier.fillMaxWidth(), shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(release.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                release.format?.takeIf { it.isNotBlank() }?.let { Badge(it.uppercase(), style = BadgeStyle.Accent) }
                Badge(formatSize(release.size))
                Badge(stringResource(R.string.release_seeders, release.seeders))
                release.bitrateKbps?.let { Badge(stringResource(R.string.release_bitrate, it)) }
                if (release.collection) Badge(stringResource(R.string.release_collection))
                release.cacheBadges().forEach { badge ->
                    if (badge.cached) Badge(stringResource(R.string.release_cached, badge.provider.label()), style = BadgeStyle.Mustard)
                    else Badge(stringResource(R.string.release_not_cached, badge.provider.label()))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.release_usable_via), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    release.usableVia().forEach { via ->
                        when (via) {
                            is UsableVia.Debrid -> Badge(stringResource(R.string.release_via_debrid, via.provider.label()), style = BadgeStyle.Accent)
                            UsableVia.BitTorrent -> Badge(stringResource(R.string.release_via_bittorrent), style = BadgeStyle.Accent)
                            UsableVia.NeedsKey -> Badge(stringResource(R.string.release_needs_key))
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (chosen) Badge(stringResource(R.string.book_release_chosen), style = BadgeStyle.Ok)
                else TextButton(onClick = onChoose) { Text(stringResource(R.string.book_release_choose), color = MaterialTheme.colorScheme.onSurface) }
                ReleaseDownloadButton(book, release)
            }
            if (files.isEmpty()) {
                Text(stringResource(R.string.book_release_files_unknown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                TextButton(onClick = { showFiles = !showFiles }) {
                    Text(
                        stringResource(R.string.book_release_files, files.size) + " · " + stringResource(if (showFiles) R.string.book_release_files_hide else R.string.book_release_files_show),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (showFiles) {
                    files.forEach { f ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            Text(f.path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatSize(f.size), style = MaterialTheme.typography.bodySmall.merge(xyz.aresius.audiobooks.core.design.TabularFigures), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** The catalog's chapters of the book (the player follows them when they fit the release's audio, else the files). */
private fun LazyListScope.chaptersSection(chapters: Load<Chapters>) {
    item {
        Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            HorizontalDivider(Modifier.padding(vertical = Spacing.s), color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.book_chapters_title), style = MaterialTheme.typography.headlineSmall)
        }
    }
    val list = (chapters as? Load.Content)?.value?.chapters.orEmpty()
    if (list.isEmpty()) {
        if (chapters !is Load.Loading) {
            item { Text(stringResource(R.string.book_chapters_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.screen)) }
        }
        return
    }
    itemsIndexed(list) { i, c ->
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text((i + 1).toString(), style = MaterialTheme.typography.labelLarge.merge(xyz.aresius.audiobooks.core.design.TabularFigures), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
            Text(c.title.ifBlank { stringResource(R.string.player_chapter_n, i + 1) }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(formatClock(c.startMs), style = MaterialTheme.typography.bodyMedium.merge(xyz.aresius.audiobooks.core.design.TabularFigures), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
