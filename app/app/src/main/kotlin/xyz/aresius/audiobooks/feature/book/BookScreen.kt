package xyz.aresius.audiobooks.feature.book

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.Badge
import xyz.aresius.audiobooks.core.design.CoverImage
import xyz.aresius.audiobooks.core.design.DetailTopBar
import xyz.aresius.audiobooks.core.design.ErrorState
import xyz.aresius.audiobooks.core.design.LoadingShimmer
import xyz.aresius.audiobooks.core.design.PrimaryButton
import xyz.aresius.audiobooks.core.design.SecondaryButton
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.ReleaseView
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.feature.common.authorLine
import xyz.aresius.audiobooks.feature.common.formatInstant
import xyz.aresius.audiobooks.feature.common.formatRuntime
import xyz.aresius.audiobooks.feature.common.formatSize
import xyz.aresius.audiobooks.feature.common.message
import xyz.aresius.audiobooks.feature.common.seriesLine

@Composable
fun BookScreen(onBack: () -> Unit, viewModel: BookViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
    Column(Modifier.fillMaxSize()) {
        val detail = state.detail
        DetailTopBar(
            title = (detail as? Load.Content)?.value?.book?.title.orEmpty(),
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
                is Load.Content -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    item { Header(detail.value.book, inLibrary = state.libraryEntry != null, busy = state.libraryBusy, onToggleLibrary = viewModel::toggleLibrary) }
                    item { Actions(onClick = viewModel::onDownloadOrPlay) }
                    detail.value.book.description?.takeIf { it.isNotBlank() }?.let { description ->
                        item {
                            Text(description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Spacing.screen))
                        }
                    }
                    item {
                        ReleasesHeader(
                            count = detail.value.releases.size,
                            searchedAt = detail.value.searchedAt,
                            stale = detail.stale,
                            refreshing = state.refreshing,
                        )
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
                    item { Column(Modifier.padding(Spacing.m)) {} }
                }
            }
        }
        SnackbarHost(snackbar)
    }
}

@Composable
private fun BookLoading() {
    Column(Modifier.fillMaxWidth().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            LoadingShimmer(Modifier.size(140.dp))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                LoadingShimmer(Modifier.size(width = 180.dp, height = 20.dp), RoundedCornerShape(4.dp))
                LoadingShimmer(Modifier.size(width = 120.dp, height = 14.dp), RoundedCornerShape(4.dp))
            }
        }
        LoadingShimmer(Modifier.fillMaxWidth().height(90.dp))
    }
}

@Composable
private fun Header(book: Book, inLibrary: Boolean, busy: Boolean, onToggleLibrary: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            CoverImage(book.coverUrl, Modifier.size(140.dp), contentDescription = book.title)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge)
                book.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                book.seriesLine()?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (book.authors.isNotEmpty()) LabeledLine(stringResource(R.string.book_by), book.authorLine())
            if (book.narrators.isNotEmpty()) LabeledLine(stringResource(R.string.book_narrated_by), book.narrators.joinToString(", "))
            LabeledLine(stringResource(R.string.book_runtime), formatRuntime(book.runtimeMin))
            book.releaseYear?.let { LabeledLine(stringResource(R.string.book_year), it.toString()) }
            if (book.language.isNotBlank()) LabeledLine(stringResource(R.string.book_language), book.language)
        }
        if (book.genres.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                book.genres.forEach { Badge(it) }
            }
        }
        SecondaryButton(
            text = stringResource(if (inLibrary) R.string.book_remove_from_library else R.string.book_add_to_library),
            onClick = onToggleLibrary,
            enabled = !busy,
        )
    }
}

@Composable
private fun LabeledLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
    }
}

/** Play and Download of the best release: the player and the download engine come in later phases. */
@Composable
private fun Actions(onClick: () -> Unit) {
    Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            PrimaryButton(stringResource(R.string.book_play), onClick = onClick, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.book_download), onClick = onClick, modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.book_coming_soon), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReleasesHeader(count: Int, searchedAt: String?, stale: Boolean, refreshing: Boolean) {
    Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HorizontalDivider(Modifier.padding(bottom = Spacing.s))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(R.string.book_releases_title, count), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
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
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(release.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                release.format?.takeIf { it.isNotBlank() }?.let { Badge(it.uppercase(), highlight = true) }
                Badge(formatSize(release.size))
                Badge(stringResource(R.string.release_seeders, release.seeders))
                release.bitrateKbps?.let { Badge(stringResource(R.string.release_bitrate, it)) }
                if (release.collection) Badge(stringResource(R.string.release_collection))
                release.cacheBadges().forEach { badge ->
                    if (badge.cached) Badge(stringResource(R.string.release_cached, badge.provider.label()), positive = true)
                    else Badge(stringResource(R.string.release_not_cached, badge.provider.label()))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.release_usable_via), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    release.usableVia().forEach { via ->
                        when (via) {
                            is UsableVia.Debrid -> Badge(stringResource(R.string.release_via_debrid, via.provider.label()), highlight = true)
                            UsableVia.BitTorrent -> Badge(stringResource(R.string.release_via_bittorrent), highlight = true)
                            UsableVia.NeedsKey -> Badge(stringResource(R.string.release_needs_key))
                        }
                    }
                }
            }
            TextButton(onClick = onDownload) { Text(stringResource(R.string.book_download)) }
        }
    }
}
