package xyz.aresius.audiobooks.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import xyz.aresius.audiobooks.feature.admin.UsersScreen
import xyz.aresius.audiobooks.feature.book.BookScreen
import xyz.aresius.audiobooks.feature.discover.DiscoverScreen
import xyz.aresius.audiobooks.feature.library.LibraryScreen
import xyz.aresius.audiobooks.feature.search.SearchScreen
import xyz.aresius.audiobooks.feature.settings.SettingsScreen

/** All destinations of the signed-in app. Screens take plain callbacks; new routes go in [Routes] and here, nowhere else. */
@Composable
fun AudiobooksNavHost(navController: NavHostController, isAdmin: Boolean, modifier: Modifier = Modifier) {
    val openBook: (String) -> Unit = { asin -> navController.navigate(Routes.Book(asin)) }
    NavHost(navController = navController, startDestination = Routes.Discover, modifier = modifier) {
        composable<Routes.Discover> { DiscoverScreen(onBook = openBook) }
        composable<Routes.Search> { SearchScreen(onBook = openBook) }
        composable<Routes.Library> { LibraryScreen(onBook = openBook) }
        composable<Routes.Settings> {
            SettingsScreen(isAdmin = isAdmin, onUsers = { navController.navigate(Routes.Users) })
        }
        composable<Routes.Book> { BookScreen(onBack = { navController.popBackStack() }) }
        composable<Routes.Users> { UsersScreen(onBack = { navController.popBackStack() }) }
    }
}
