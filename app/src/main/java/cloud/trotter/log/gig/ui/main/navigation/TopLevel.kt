package cloud.trotter.log.gig.ui.main.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.ui.graphics.vector.ImageVector
import cloud.trotter.log.gig.R

/**
 * The three tabs of the bottom navigation bar (#1447): Home (today), Analytics (the past), Playbook
 * (the next move). Each owns one root route; every other screen sits on a tab's stack.
 */
enum class TopLevel(val route: String, @param:StringRes val labelRes: Int, val icon: ImageVector) {
    HOME(Screen.Dashboard.route, R.string.home_screen_title, Icons.Filled.Home),
    ANALYTICS(Screen.Analytics.route, R.string.analytics_screen_title, Icons.Filled.BarChart),
    PLAYBOOK(Screen.Playbook.route, R.string.playbook_screen_title, Icons.Filled.Map);

    companion object {
        /** The tab whose ROOT is [route], or null for any screen below a tab (or no route). */
        fun fromRoute(route: String?): TopLevel? = entries.firstOrNull { it.route == route }
    }
}
