package cloud.trotter.log.gig.ui.main.navigation

/**
 * Where a route is delivered (#1447): pushed over the current screen, or opened on a tab.
 *
 * [Tab.fresh] opens the tab as a new root (its parked place is dropped); [Tab.child] is pushed on top
 * of the tab's root. Off a tab root a [Tab] target with a child only pushes the child, so nothing
 * under the current screen is ever popped (see [deliver]).
 */
sealed interface RouteTarget {
    data class Push(val route: String) : RouteTarget
    data class Tab(val tab: TopLevel, val child: String?, val fresh: Boolean) : RouteTarget
}

object RouteTargets {
    /** The one owner of "the plan opens in Playbook, fresh" — the notification and Home's plan row. */
    val WeeklyPlan: RouteTarget.Tab = RouteTarget.Tab(TopLevel.PLAYBOOK, child = Screen.WeeklyPlan.route, fresh = true)
}

/**
 * The target for an external route (a deep link, #693): null for anything outside the fail-closed
 * [Screen.allRoutes] allowlist, the plan's [RouteTargets.WeeklyPlan], a restoring [RouteTarget.Tab]
 * for a tab root, and a [RouteTarget.Push] for the rest.
 */
fun routeTarget(route: String): RouteTarget? = when {
    route !in Screen.allRoutes -> null
    route == Screen.WeeklyPlan.route -> RouteTargets.WeeklyPlan
    else -> TopLevel.fromRoute(route)?.let { RouteTarget.Tab(it, child = null, fresh = false) }
        ?: RouteTarget.Push(route)
}
