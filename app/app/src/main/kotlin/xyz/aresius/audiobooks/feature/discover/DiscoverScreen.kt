package xyz.aresius.audiobooks.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.ColorMath
import xyz.aresius.audiobooks.core.design.CoverImage
import xyz.aresius.audiobooks.core.design.CoverShape
import xyz.aresius.audiobooks.core.design.EmptyState
import xyz.aresius.audiobooks.core.design.ErrorState
import xyz.aresius.audiobooks.core.design.ShimmerShelf
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.design.TabularFigures
import xyz.aresius.audiobooks.core.design.WavyProgressBar
import xyz.aresius.audiobooks.core.design.coverShadow
import xyz.aresius.audiobooks.core.design.extras
import xyz.aresius.audiobooks.core.design.rememberCoverColors
import xyz.aresius.audiobooks.core.network.LibraryEntry
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.feature.common.ShelfCover
import xyz.aresius.audiobooks.feature.common.authorLine
import xyz.aresius.audiobooks.feature.common.formatRuntime
import xyz.aresius.audiobooks.feature.common.message
import xyz.aresius.audiobooks.feature.common.progressFraction

@Composable
private fun greetingText(greeting: Greeting, name: String?): String {
    val who = name?.takeIf { it.isNotBlank() }
    return when (greeting) {
        Greeting.Morning -> if (who != null) stringResource(R.string.greeting_morning, who) else stringResource(R.string.greeting_morning_anon)
        Greeting.Afternoon -> if (who != null) stringResource(R.string.greeting_afternoon, who) else stringResource(R.string.greeting_afternoon_anon)
        Greeting.Evening -> if (who != null) stringResource(R.string.greeting_evening, who) else stringResource(R.string.greeting_evening_anon)
        Greeting.Night -> if (who != null) stringResource(R.string.greeting_night, who) else stringResource(R.string.greeting_night_anon)
    }
}

/** Editorial Discover: a serif greeting, the "Continue listening" hero straight on the page (no card), then shelves of large covers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(onBook: (String) -> Unit, onPlay: (LibraryEntry) -> Unit, viewModel: DiscoverViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResumed()
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxSize()) {
        when (val books = state.books) {
            Load.Loading -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.l)) {
                item { Greeting(state) }
                item { ShimmerShelf() }
            }
            is Load.Failed -> LazyColumn(Modifier.fillMaxSize()) {
                item { Greeting(state) }
                item { ErrorState(books.error.message(), onRetry = { viewModel.load() }) }
            }
            is Load.Content -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { viewModel.load() }, modifier = Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()
                val shelfList = shelves(books.value)
                LaunchedEffect(listState, shelfList.size) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                        .distinctUntilChanged()
                        .filter { it >= listState.layoutInfo.totalItemsCount - 2 }
                        .collect { viewModel.loadMore() }
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = Spacing.l)) {
                    item { Greeting(state) }
                    state.continueListening?.let { entry ->
                        item { ContinueListening(entry, onClick = { onBook(entry.book.asin) }, onPlay = { onPlay(entry) }) }
                        item { HorizontalDivider(Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.l), color = MaterialTheme.colorScheme.outlineVariant) }
                    }
                    if (books.stale) {
                        item {
                            Text(
                                stringResource(R.string.common_offline_copy),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
                            )
                        }
                    }
                    if (books.value.isEmpty()) {
                        item { EmptyState(stringResource(R.string.discover_empty_title), message = stringResource(R.string.discover_empty_message)) }
                    }
                    itemsIndexed(shelfList) { index, shelf ->
                        Shelf(
                            title = stringResource(if (index == 0) R.string.discover_shelf_first else R.string.discover_shelf_more),
                            books = shelf,
                            onBook = onBook,
                        )
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s), horizontalArrangement = Arrangement.Center) {
                            when {
                                state.loadingMore -> CircularProgressIndicator(Modifier.padding(Spacing.s))
                                state.loadMoreError != null -> TextButton(onClick = viewModel::loadMore) { Text(stringResource(R.string.discover_load_more_failed)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Greeting(state: DiscoverUiState) {
    Text(
        greetingText(state.greeting, state.name),
        style = MaterialTheme.typography.displayMedium,
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xl, bottom = Spacing.l),
    )
}

@Composable
private fun Shelf(title: String, books: List<xyz.aresius.audiobooks.core.network.Book>, onBook: (String) -> Unit) {
    Column(Modifier.padding(bottom = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Row(Modifier.padding(horizontal = Spacing.screen), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(8.dp).background(MaterialTheme.extras.accentText, CircleShape))
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Spacing.screen), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            items(books, key = { it.asin }) { book -> ShelfCover(book, onClick = { onBook(book.asin) }) }
        }
    }
}

/**
 * Variant B of the hero: no card, everything on the page. The cover (24 dp radius) has a soft glow of its own palette in the dark theme
 * and a soft warm shadow in the light theme; below it the serif title, the author, a resting wavy progress, the time left and the round
 * persimmon play button, which starts the book and opens the player; the rest of the hero opens the book page.
 */
@Composable
private fun ContinueListening(entry: LibraryEntry, onClick: () -> Unit, onPlay: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val cover by rememberCoverColors(entry.book.coverUrl)
    val progress = progressFraction(entry.positionMs, entry.book.runtimeMin)
    val leftMin = (entry.book.runtimeMin - (entry.positionMs / 60_000L).toInt()).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text(stringResource(R.string.discover_continue), style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            val glow = cover?.glow?.let { ColorMath.scaleSaturation(it.copy(alpha = 1f), 0.8f).copy(alpha = 0.30f) } ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            Box(
                Modifier.size(110.dp).then(
                    if (dark) {
                        Modifier.drawBehind {
                            drawCircle(
                                Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(size.width / 2, size.height / 2), radius = size.maxDimension * 0.95f),
                                radius = size.maxDimension * 0.95f,
                            )
                        }
                    } else {
                        Modifier.coverShadow(CoverShape)
                    },
                ),
            ) {
                CoverImage(entry.book.coverUrl, Modifier.size(110.dp), shape = CoverShape, contentDescription = entry.book.title)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.book.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3)
                val authors = entry.book.authorLine()
                if (authors.isNotEmpty()) Text(authors, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
        WavyProgressBar(progress, restingAmplitude = 0.55f, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.discover_time_left, formatRuntime(leftMin)),
                style = MaterialTheme.typography.bodyMedium.merge(TabularFigures),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, CircleShape).clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.book_play), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp))
            }
        }
    }
}
