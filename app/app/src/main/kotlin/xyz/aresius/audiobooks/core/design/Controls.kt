package xyz.aresius.audiobooks.core.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Filter chip: selected = accent, unselected = surfaceVariant. */
@Composable
fun PillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(PillShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

enum class BadgeStyle {
    /** Muted ink on a quiet tone. */
    Neutral,

    /** The accent tint: "usable via". */
    Accent,

    /** Mustard with ink text: "cached". */
    Mustard,

    /** Ink with a check mark: "ok" / "has a key". Never green. */
    Ok,
}

/** Small read-only label ("M4B", "Cached", "Debrid"). Status is carried by an icon and the word as well as by color. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, style: BadgeStyle = BadgeStyle.Neutral) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (style) {
        BadgeStyle.Neutral -> scheme.surfaceVariant to scheme.onSurfaceVariant
        BadgeStyle.Accent -> scheme.primaryContainer to scheme.onPrimaryContainer
        BadgeStyle.Mustard -> MaterialTheme.extras.mustard to MaterialTheme.extras.onMustard
        BadgeStyle.Ok -> scheme.surfaceVariant to scheme.onSurface
    }
    Row(
        modifier.clip(PillShape).background(container).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (style == BadgeStyle.Ok) Icon(Icons.Rounded.Check, contentDescription = null, tint = content, modifier = Modifier.size(12.dp))
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

/**
 * Button labels are 20 sp bold: that counts as large text in WCAG, which is what paper on persimmon (3.7:1) needs; smaller accent text
 * uses [AudiobooksExtras.accentText] instead.
 */
private val ButtonLabel
    @Composable get() = MaterialTheme.typography.labelLarge.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)

/** Full-width accent pill; shows a spinner and ignores clicks while [loading]. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = if (loading) 0.8f else 0.35f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = if (loading) 1f else 0.6f),
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.5.dp)
        } else {
            // Longer translations ("Verificar y guardar" in a half-width button) shrink to one line instead of being clipped by the fixed height.
            Text(text, style = ButtonLabel, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 20.sp, stepSize = 1.sp))
        }
    }
}

/** Full-width outlined pill for the secondary action next to a [PrimaryButton]. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = PillShape,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
    ) {
        // Ink on paper has contrast to spare, so a long label ("Downloading 45%") shrinks to one line instead of wrapping.
        Text(text, style = ButtonLabel, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 20.sp, stepSize = 1.sp))
    }
}

/**
 * The quiet pill for secondary actions inside cards: ink label (14 sp medium, one line, never wraps), hairline outline, 48 dp high so every
 * card lines up. The loud action of a card is a [PrimaryButton].
 */
@Composable
fun CompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = PillShape,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Preview
@Composable
private fun ControlsPreview() = AudiobooksPreviewSurface {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PillChip("All", selected = true, onClick = {})
            PillChip("Listening", selected = false, onClick = {})
            Badge("M4B")
            Badge("Cached", style = BadgeStyle.Mustard)
            Badge("Debrid", style = BadgeStyle.Accent)
            Badge("Working", style = BadgeStyle.Ok)
        }
        PrimaryButton("Continue", onClick = {})
        PrimaryButton("Continue", onClick = {}, loading = true)
        SecondaryButton("Skip", onClick = {})
        CompactButton("Download this release", onClick = {})
    }
}
