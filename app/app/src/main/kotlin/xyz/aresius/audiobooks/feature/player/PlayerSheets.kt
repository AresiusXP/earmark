package xyz.aresius.audiobooks.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.PillChip
import xyz.aresius.audiobooks.core.design.PrimaryButton
import xyz.aresius.audiobooks.core.design.SecondaryButton
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.design.TabularFigures
import xyz.aresius.audiobooks.core.design.EmptyState
import xyz.aresius.audiobooks.core.player.BookChapter
import xyz.aresius.audiobooks.core.player.ChapterSource
import xyz.aresius.audiobooks.core.player.PlaybackRules
import xyz.aresius.audiobooks.core.player.PlayerBookmark
import xyz.aresius.audiobooks.core.player.PlayerState
import xyz.aresius.audiobooks.core.player.SleepSetting
import xyz.aresius.audiobooks.core.player.SleepState
import xyz.aresius.audiobooks.core.player.SleepTimerLogic

@Composable
fun BookChapter.displayTitle(): String = title.ifBlank { stringResource(R.string.player_chapter_n, index + 1) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(onDismiss: () -> Unit, title: String, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = Spacing.screen))
            content()
        }
    }
}

// --- chapters ------------------------------------------------------------------------------------------------------

@Composable
fun ChaptersSheet(state: PlayerState, onPick: (BookChapter) -> Unit, onDismiss: () -> Unit) {
    PlayerSheet(onDismiss, stringResource(R.string.player_chapters)) {
        if (state.chapterSource == ChapterSource.Files) {
            Text(
                stringResource(R.string.player_chapters_from_files),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.screen),
            )
        }
        val listState = rememberLazyListState()
        LaunchedEffect(Unit) { if (state.chapterIndex > 2) listState.scrollToItem(state.chapterIndex - 2) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), state = listState) {
            itemsIndexed(state.chapters, key = { _, c -> c.index }) { index, chapter ->
                val current = index == state.chapterIndex
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(chapter) }.padding(horizontal = Spacing.screen, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    Text(
                        (index + 1).toString(),
                        style = MaterialTheme.typography.labelLarge.merge(TabularFigures),
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        chapter.displayTitle(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    Text(formatClock(chapter.startMs), style = MaterialTheme.typography.bodyMedium.merge(TabularFigures), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (index < state.chapters.lastIndex) HorizontalDivider(Modifier.padding(horizontal = Spacing.screen), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

// --- speed ---------------------------------------------------------------------------------------------------------

private val SPEED_PRESETS = listOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

@Composable
fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    PlayerSheet(onDismiss, stringResource(R.string.player_speed)) {
        Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { onSpeed(speed - 0.1f) }) { Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.player_speed_slower)) }
                Text(
                    stringResource(R.string.player_speed_value, formatSpeed(speed)),
                    style = MaterialTheme.typography.displaySmall.merge(TabularFigures),
                )
                IconButton(onClick = { onSpeed(speed + 0.1f) }) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.player_speed_faster)) }
            }
            Slider(
                value = speed,
                onValueChange = { onSpeed(it) },
                valueRange = PlaybackRules.MIN_SPEED..PlaybackRules.MAX_SPEED,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onSpeed(speed - 0.05f) }) { Text("−0.05") }
                TextButton(onClick = { onSpeed(speed + 0.05f) }) { Text("+0.05") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEED_PRESETS.forEach { preset ->
                    PillChip(stringResource(R.string.player_speed_value, formatSpeed(preset)), selected = speed == preset, onClick = { onSpeed(preset) })
                }
            }
            Text(stringResource(R.string.player_speed_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --- sleep timer ---------------------------------------------------------------------------------------------------

@Composable
fun SleepSheet(sleep: SleepState, onStart: (SleepSetting) -> Unit, onExtend: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    PlayerSheet(onDismiss, stringResource(R.string.player_sleep)) {
        Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (sleep is SleepState.Running) {
                Text(sleepLabel(sleep), style = MaterialTheme.typography.titleLarge.merge(TabularFigures))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    PrimaryButton(stringResource(R.string.player_sleep_extend), onClick = onExtend, modifier = Modifier.weight(1f))
                }
                SecondaryButton(stringResource(R.string.player_sleep_cancel), onClick = { onCancel(); onDismiss() })
            }
            SleepOption(stringResource(R.string.player_sleep_end_of_chapter), selected = (sleep as? SleepState.Running)?.setting == SleepSetting.EndOfChapter) {
                onStart(SleepSetting.EndOfChapter)
                onDismiss()
            }
            SleepTimerLogic.MINUTE_OPTIONS.forEach { minutes ->
                SleepOption(stringResource(R.string.player_sleep_minutes, minutes), selected = (sleep as? SleepState.Running)?.setting == SleepSetting.Minutes(minutes)) {
                    onStart(SleepSetting.Minutes(minutes))
                    onDismiss()
                }
            }
            Text(stringResource(R.string.player_sleep_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SleepOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** "Sleep in 12:30" / "End of chapter · 4:10". */
@Composable
fun sleepLabel(sleep: SleepState.Running): String = when (sleep.setting) {
    SleepSetting.EndOfChapter -> stringResource(R.string.player_sleep_running_chapter, formatClock(sleep.remainingMs))
    is SleepSetting.Minutes -> stringResource(R.string.player_sleep_running, formatClock(sleep.remainingMs))
}

// --- bookmarks -----------------------------------------------------------------------------------------------------

@Composable
fun BookmarksSheet(
    bookmarks: List<PlayerBookmark>,
    positionMs: Long,
    onAdd: (String?) -> Unit,
    onJump: (PlayerBookmark) -> Unit,
    onDelete: (PlayerBookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSheet(onDismiss, stringResource(R.string.player_bookmarks)) {
        var note by remember { mutableStateOf("") }
        Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(280) },
                label = { Text(stringResource(R.string.player_bookmark_note)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(stringResource(R.string.player_bookmark_add_here, formatClock(positionMs)), onClick = {
                onAdd(note.ifBlank { null })
                note = ""
            })
        }
        if (bookmarks.isEmpty()) {
            EmptyState(stringResource(R.string.player_bookmark_empty_title), message = stringResource(R.string.player_bookmark_empty_message))
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                itemsIndexed(bookmarks, key = { _, b -> b.id }) { _, b ->
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onJump(b) }.padding(start = Spacing.screen, end = Spacing.xs, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    ) {
                        Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(formatClock(b.positionMs), style = MaterialTheme.typography.titleMedium.merge(TabularFigures))
                            Text(
                                b.note ?: stringResource(R.string.player_bookmark_no_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        if (!b.synced) Icon(Icons.Rounded.CloudOff, contentDescription = stringResource(R.string.player_bookmark_unsynced), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        IconButton(onClick = { onDelete(b) }) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.player_bookmark_delete)) }
                    }
                }
            }
        }
    }
}
