package cloud.trotter.log.gig.ui.main.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import cloud.trotter.log.gig.R

/**
 * The three tabs of the bottom navigation bar (#1447): Home (today), Recap (the past), Plan (the next
 * move). Each owns one root route; every other screen sits on a tab's stack. The enum and route names
 * keep their older ANALYTICS and PLAYBOOK spelling.
 */
enum class TopLevel(val route: String, @param:StringRes val labelRes: Int) {
    HOME(Screen.Dashboard.route, R.string.home_screen_title),
    ANALYTICS(Screen.Analytics.route, R.string.analytics_screen_title),
    PLAYBOOK(Screen.Playbook.route, R.string.playbook_screen_title);

    companion object {
        /** The tab whose ROOT is [route], or null for any screen below a tab (or no route). */
        fun fromRoute(route: String?): TopLevel? = entries.firstOrNull { it.route == route }
    }
}

/** The tab's bar icon. Plan's `calendar_clock` is not in the Compose icon set, so it is a drawable. */
val TopLevel.icon: ImageVector
    @Composable get() = when (this) {
        TopLevel.HOME -> Icons.Filled.Home
        TopLevel.ANALYTICS -> Icons.Filled.BarChart
        TopLevel.PLAYBOOK -> ImageVector.vectorResource(R.drawable.ic_calendar_clock)
    }
