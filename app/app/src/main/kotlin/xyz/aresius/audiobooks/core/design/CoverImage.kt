package xyz.aresius.audiobooks.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Remote cover (square) with a neutral placeholder that stays visible if loading fails. [shadow] gives it the app's one soft cover shadow;
 * [asin] makes it a shared element between a list and the Book detail (one cover per book per screen).
 */
@Composable
fun CoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = CoverShape,
    contentDescription: String? = null,
    shadow: Boolean = false,
    asin: String? = null,
) {
    var outer = modifier
    if (asin != null) outer = outer.coverSharedElement(asin)
    if (shadow) outer = outer.coverShadow(shape)
    Box(
        modifier = outer.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Headphones,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxSize(0.4f),
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview
@Composable
private fun CoverImagePreview() = AudiobooksPreviewSurface {
    CoverImage(null, Modifier.padding(16.dp).size(120.dp), shadow = true)
}
