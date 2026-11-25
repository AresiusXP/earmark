package xyz.aresius.audiobooks.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import xyz.aresius.audiobooks.feature.onboarding.OnboardingScreen
import xyz.aresius.audiobooks.feature.update.UpdateBanner

private fun NavDestination?.topLevelTab(): TopLevelTab? = when {
    this == null -> null
    hasRoute<Routes.Discover>() -> TopLevelTab.Discover
    hasRoute<Routes.Search>() -> TopLevelTab.Search
    hasRoute<Routes.Library>() -> TopLevelTab.Library
    hasRoute<Routes.Settings>() -> TopLevelTab.Settings
    else -> null
}

/** Session gate: onboarding while signed out (or before its last steps are done), else the bottom-bar shell. */
@Composable
fun AudiobooksAppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val appState by viewModel.appState.collectAsStateWithLifecycle()
    when (appState) {
        AppState.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        AppState.Onboarding -> OnboardingScreen()
        AppState.Main -> AppShell(viewModel)
    }
}

@Composable
private fun AppShell(viewModel: AppViewModel) {
    val navController = rememberNavController()
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    var lastTab by rememberSaveable { mutableStateOf(TopLevelTab.Discover) }
    destination.topLevelTab()?.let { lastTab = it }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column {
                UpdateBanner()
                AudiobooksBottomBar(selected = lastTab, onSelect = { tab ->
                    if (tab == lastTab && destination.topLevelTab() == null) {
                        // Re-selecting the current tab from one of its detail screens goes back to the tab's root.
                        navController.popBackStack(tab.route, inclusive = false)
                        return@AudiobooksBottomBar
                    }
                    navController.navigate(tab.route) {
                        popUpTo<Routes.Discover> { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
        },
    ) { innerPadding ->
        AudiobooksNavHost(
            navController = navController,
            isAdmin = isAdmin,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        )
    }
}
