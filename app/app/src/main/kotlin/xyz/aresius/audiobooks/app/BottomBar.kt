package xyz.aresius.audiobooks.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.AudiobooksPreviewSurface

enum class TopLevelTab(@StringRes val label: Int, val route: Any, val icon: ImageVector, val selectedIcon: ImageVector) {
    Discover(R.string.tab_discover, Routes.Discover, Icons.Outlined.Explore, Icons.Rounded.Explore),
    Search(R.string.tab_search, Routes.Search, Icons.Outlined.Search, Icons.Rounded.Search),
    Library(R.string.tab_library, Routes.Library, Icons.AutoMirrored.Outlined.LibraryBooks, Icons.AutoMirrored.Rounded.LibraryBooks),
    Settings(R.string.tab_settings, Routes.Settings, Icons.Outlined.Settings, Icons.Rounded.Settings),
}

@Composable
fun AudiobooksBottomBar(selected: TopLevelTab, onSelect: (TopLevelTab) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = androidx.compose.ui.unit.Dp.Hairline,
    ) {
        TopLevelTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val label = stringResource(tab.label)
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(tab) },
                icon = { Icon(if (isSelected) tab.selectedIcon else tab.icon, contentDescription = label) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }
    }
}

@Preview
@Composable
private fun BottomBarPreview() = AudiobooksPreviewSurface {
    AudiobooksBottomBar(TopLevelTab.Discover, onSelect = {})
}
