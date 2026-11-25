package xyz.aresius.audiobooks.feature.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.EmptyState
import xyz.aresius.audiobooks.core.design.ErrorState
import xyz.aresius.audiobooks.core.design.ScreenTitle
import xyz.aresius.audiobooks.core.design.ShimmerTrackRows
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.feature.common.BookRow
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.feature.common.formatRuntime
import xyz.aresius.audiobooks.feature.common.message
import xyz.aresius.audiobooks.feature.common.seriesLine

@Composable
fun SearchScreen(onBook: (String) -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.search_title))
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.search_clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        )
        when (val results = state.results) {
            SearchResultsState.Idle -> EmptyState(stringResource(R.string.search_idle_title), message = stringResource(R.string.search_idle_message))
            SearchResultsState.TooShort -> EmptyState(stringResource(R.string.search_too_short))
            is SearchResultsState.Results -> when (val load = results.load) {
                Load.Loading -> ShimmerTrackRows()
                is Load.Failed -> ErrorState(load.error.message(), onRetry = viewModel::retry)
                is Load.Content -> if (load.value.isEmpty()) {
                    EmptyState(stringResource(R.string.search_no_results_title), message = stringResource(R.string.search_no_results_message, state.query.trim()))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(load.value, key = { it.asin }) { book ->
                            val runtime = formatRuntime(book.runtimeMin)
                            val supporting = listOfNotNull(book.seriesLine(), runtime).joinToString(" · ")
                            BookRow(book, onClick = { onBook(book.asin) }, supporting = supporting)
                        }
                    }
                }
            }
        }
    }
}
