package xyz.aresius.audiobooks.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.EmptyState
import xyz.aresius.audiobooks.core.design.ErrorState
import xyz.aresius.audiobooks.core.design.LoadingShimmer
import xyz.aresius.audiobooks.core.design.ScreenTitle
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.feature.common.BookCard
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.feature.common.message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(onBook: (String) -> Unit, viewModel: DiscoverViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.discover_title))
        when (val books = state.books) {
            Load.Loading -> LoadingGrid()
            is Load.Failed -> ErrorState(books.error.message(), onRetry = { viewModel.load() })
            is Load.Content -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { viewModel.load() }, modifier = Modifier.fillMaxSize()) {
                if (books.value.isEmpty()) {
                    EmptyState(stringResource(R.string.discover_empty_title), message = stringResource(R.string.discover_empty_message))
                } else {
                    val gridState = rememberLazyGridState()
                    LaunchedEffect(gridState, books.value.size) {
                        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                            .distinctUntilChanged()
                            .filter { it >= books.value.size - 4 }
                            .collect { viewModel.loadMore() }
                    }
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(140.dp),
                        contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (books.stale) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    stringResource(R.string.common_offline_copy),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(books.value, key = { it.asin }) { book -> BookCard(book, onClick = { onBook(book.asin) }) }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                                when {
                                    state.loadingMore -> CircularProgressIndicator(Modifier.padding(8.dp))
                                    state.loadMoreError != null -> TextButton(onClick = viewModel::loadMore) { Text(stringResource(R.string.discover_load_more_failed)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingGrid() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) {
        items(8) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LoadingShimmer(Modifier.fillMaxWidth().aspectRatio(1f))
                LoadingShimmer(Modifier.fillMaxWidth(0.8f).height(14.dp), RoundedCornerShape(4.dp))
            }
        }
    }
}
