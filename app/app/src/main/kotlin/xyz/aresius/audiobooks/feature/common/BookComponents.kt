package xyz.aresius.audiobooks.feature.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.aresius.audiobooks.core.design.CoverImage
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.design.WavyProgressBar
import xyz.aresius.audiobooks.core.network.Book

/** "Author One, Author Two" (empty when unknown). */
fun Book.authorLine(): String = authors.joinToString(", ")

/** "Series name #3" or just the name. */
fun Book.seriesLine(): String? = series?.takeIf { it.isNotBlank() }?.let { s -> seriesPosition?.takeIf { it.isNotBlank() }?.let { "$s #$it" } ?: s }

/** Large squarish cover with its serif title under it: the unit of a Discover shelf. */
@Composable
fun ShelfCover(book: Book, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 168.dp) {
    Column(modifier.width(size).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CoverImage(book.coverUrl, Modifier.size(size), shadow = true, contentDescription = book.title, asin = book.asin)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val authors = book.authorLine()
            if (authors.isNotEmpty()) {
                Text(authors, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** List row for search results and the library; [progress] (0..1) draws a small wavy bar under the text when set. */
@Composable
fun BookRow(book: Book, onClick: () -> Unit, modifier: Modifier = Modifier, supporting: String? = null, progress: Float? = null) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.screen, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(book.coverUrl, Modifier.size(76.dp), shape = RoundedCornerShape(20.dp), contentDescription = book.title, asin = book.asin)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val authors = book.authorLine()
            if (authors.isNotEmpty()) {
                Text(authors, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (progress != null) {
                WavyProgressBar(progress, Modifier.padding(top = 4.dp), height = 18.dp, strokeWidth = 3.dp, waveLength = 28.dp, restingAmplitude = 0.5f, showThumb = false)
            }
        }
    }
}
