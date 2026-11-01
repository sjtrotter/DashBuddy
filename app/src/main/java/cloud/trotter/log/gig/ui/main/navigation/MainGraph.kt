package cloud.trotter.log.gig.ui.main.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/**
 * The app's one flat graph (#1447): every [Screen.graphRoutes] entry, with the two argument routes'
 * `navArgument`s, rendered by [render]. The NavHost passes the real screens and the navigation test
 * passes placeholders, so both run the same graph.
 */
fun NavGraphBuilder.mainGraph(render: @Composable (route: String, entry: NavBackStackEntry) -> Unit) {
    for (route in Screen.graphRoutes) {
        val arguments = when (route) {
            Screen.SessionDetail.route ->
                listOf(navArgument(Screen.SessionDetail.ARG_SESSION_ID) { type = NavType.StringType })
            Screen.VehicleEdit.route ->
                listOf(navArgument(Screen.VehicleEdit.ARG) { type = NavType.StringType })
            else -> emptyList()
        }
        composable(route, arguments = arguments) { entry -> render(route, entry) }
    }
}
