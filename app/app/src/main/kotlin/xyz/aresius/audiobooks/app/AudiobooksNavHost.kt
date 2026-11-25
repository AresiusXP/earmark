package xyz.aresius.audiobooks.app

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import xyz.aresius.audiobooks.core.design.LocalNavAnimatedScope
import xyz.aresius.audiobooks.core.design.LocalSharedTransitionScope
import xyz.aresius.audiobooks.feature.admin.UsersScreen
import xyz.aresius.audiobooks.feature.book.BookScreen
import xyz.aresius.audiobooks.feature.discover.DiscoverScreen
import xyz.aresius.audiobooks.feature.library.LibraryScreen
import xyz.aresius.audiobooks.feature.search.SearchScreen
import xyz.aresius.audiobooks.feature.settings.SettingsScreen

private inline fun <reified T : Any> NavGraphBuilder.screen(crossinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit) {
    composable<T> { entry ->
        // A cover in a list flies into the Book detail: the scopes travel as composition locals (see core/design/SharedElements).
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) }
    }
}

/** All destinations of the signed-in app. Screens take plain callbacks; new routes go in [Routes] and here, nowhere else. */
@Composable
fun AudiobooksNavHost(navController: NavHostController, isAdmin: Boolean, modifier: Modifier = Modifier) {
    val openBook: (String) -> Unit = { asin -> navController.navigate(Routes.Book(asin)) }
    SharedTransitionLayout(modifier) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = navController,
                startDestination = Routes.Discover,
                enterTransition = { fadeIn(tween(220)) },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = { fadeOut(tween(160)) },
            ) {
                screen<Routes.Discover> { DiscoverScreen(onBook = openBook) }
                screen<Routes.Search> { SearchScreen(onBook = openBook) }
                screen<Routes.Library> { LibraryScreen(onBook = openBook) }
                screen<Routes.Settings> { SettingsScreen(isAdmin = isAdmin, onUsers = { navController.navigate(Routes.Users) }) }
                screen<Routes.Book> { BookScreen(onBack = { navController.popBackStack() }) }
                screen<Routes.Users> { UsersScreen(onBack = { navController.popBackStack() }) }
            }
        }
    }
}
