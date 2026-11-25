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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import xyz.aresius.audiobooks.core.network.ReleaseView
import xyz.aresius.audiobooks.feature.common.Load
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
fun BookScreen(onBack: () -> Unit, viewModel: BookViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val coverUrl = (state.detail as? Load.Content)?.value?.book?.coverUrl
    val cover by rememberCoverColors(coverUrl)
    val scheme = coverColorScheme(MaterialTheme.colorScheme, cover)
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        BookContent(state, onBack, viewModel, glow = cover?.glow)
    }
}

@Composable
private fun BookContent(state: BookUiState, onBack: () -> Unit, viewModel: BookViewModel, glow: Color?) {
    val snackbar = remember { SnackbarHostState() }
    val comingSoon = stringResource(R.string.book_coming_soon)
    val errorText = state.error?.message()
    LaunchedEffect(state.comingSoon) {
        if (state.comingSoon) {
            snackbar.showSnackbar(comingSoon)
            viewModel.consumeComingSoon()
        }
    }
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText)
            viewModel.consumeError()
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
                        Hero(detail.value.book, inLibrary = state.libraryEntry != null, busy = state.libraryBusy, glow = glow, onToggleLibrary = viewModel::toggleLibrary, onPlayOrDownload = viewModel::onDownloadOrPlay)
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
                        ReleaseCard(release, onDownload = viewModel::onDownloadOrPlay, modifier = Modifier.padding(horizontal = Spacing.screen))
                    }
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
private fun Hero(book: Book, inLibrary: Boolean, busy: Boolean, glow: Color?, onToggleLibrary: () -> Unit, onPlayOrDownload: () -> Unit) {
    val dark = isSystemInDarkTheme()
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Box(
            Modifier.size(224.dp).then(
                if (dark && glow != null) {
                    Modifier.drawBehind {
                        drawCircle(
                            Brush.radialGradient(listOf(glow.copy(alpha = 0.30f), Color.Transparent), center = Offset(size.width / 2, size.height / 2), radius = size.maxDimension * 0.9f),
                            radius = size.maxDimension * 0.9f,
                        )
                    }
                } else {
                    Modifier.coverShadow(CoverShape)
                },
            ),
        ) {
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            PrimaryButton(stringResource(R.string.book_play), onClick = onPlayOrDownload, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.book_download), onClick = onPlayOrDownload, modifier = Modifier.weight(1f))
        }
        SecondaryButton(
            text = stringResource(if (inLibrary) R.string.book_remove_from_library else R.string.book_add_to_library),
            onClick = onToggleLibrary,
            enabled = !busy,
        )
        Text(stringResource(R.string.book_coming_soon), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
private fun ReleaseCard(release: ReleaseView, onDownload: () -> Unit, modifier: Modifier = Modifier) {
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
            TextButton(onClick = onDownload) { Text(stringResource(R.string.book_download), color = MaterialTheme.colorScheme.onSurface) }
        }
    }
}
