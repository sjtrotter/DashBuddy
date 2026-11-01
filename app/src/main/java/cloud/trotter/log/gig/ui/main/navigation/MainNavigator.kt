package cloud.trotter.log.gig.ui.main.navigation

import androidx.navigation.NavController
import timber.log.Timber

/*
 * The tab navigator (#1447), as extensions on the one NavController. The graph is flat; the tab roots
 * are Dashboard, Analytics and Playbook, and a non-Home tab's stack is `[Dashboard, <root>, ...]`.
 *
 * Two rules carry it (the runtime facts are in the #1447 spec, from navigation 2.9.7):
 *  1. Dashboard is never a saved-state key, and nothing calls `clearBackStack(String)` — that overload
 *     drops Dashboard's empty mapping, after which a later save keys Dashboard to another tab's stack.
 *  2. Tab switching, parking and fresh-clearing run only with a tab root as the current destination.
 *     Off a root they fail closed. So a parked tab is always one lone root, and no editor or draft is
 *     ever popped by the navigator. Ordinary child Back still pops off a root, as always.
 */

private const val TAG = "Navigation"

/** True while a tab root is the current destination. */
fun NavController.atRoot(): Boolean = TopLevel.fromRoute(currentDestination?.route) != null

/**
 * The tab whose stack is showing: Analytics or Playbook when its root is on the back stack, else Home.
 * Probes with the public `getBackStackEntry`; `currentBackStack` is restricted API in 2.9.7.
 */
fun NavController.currentTab(): TopLevel =
    listOf(TopLevel.ANALYTICS, TopLevel.PLAYBOOK).firstOrNull { tab ->
        runCatching { getBackStackEntry(tab.route) }.isSuccess
    } ?: TopLevel.HOME

/** Park the current tab: from Analytics or Playbook pop exactly its root, saving it. Home pops nothing. */
private fun NavController.leaveCurrentTab() {
    val tab = currentTab()
    if (tab != TopLevel.HOME) popBackStack(tab.route, inclusive = true, saveState = true)
}

/** The bar: switch to [tab], restoring its parked place. A reselect does nothing. */
fun NavController.selectTab(tab: TopLevel) {
    if (!atRoot()) {
        Timber.tag(TAG).w("Tab switch refused off a tab root (#1447)")
        return
    }
    if (tab == currentTab()) return
    leaveCurrentTab()
    if (tab != TopLevel.HOME) navigate(tab.route) { restoreState = true }
}

/** Open [tab] as a new root (its parked place dropped), then push [child] on top of it. */
fun NavController.openTabFresh(tab: TopLevel, child: String? = null) {
    if (!atRoot()) {
        Timber.tag(TAG).w("Fresh tab open refused off a tab root (#1447)")
        return
    }
    if (tab != currentTab()) {
        leaveCurrentTab()
        if (tab != TopLevel.HOME) {
            // The Int overload: a no-op when nothing is saved, and it never touches another key.
            graph.findNode(tab.route)?.id?.let { clearBackStack(it) }
            navigate(tab.route)
        }
    }
    child?.let { push(it) }
}

/**
 * Push [route] over the current screen; single-top, so a repeat adds nothing. Single-top only sees the
 * top: a second plan link after another push (plan, then bubble Settings, then plan) stacks a second
 * plan screen, as master did. Each Back pops one.
 */
fun NavController.push(route: String) {
    navigate(route) { launchSingleTop = true }
}

/**
 * Deliver [target] (deep links and Home's plan row). At a tab root a [RouteTarget.Tab] switches or
 * opens fresh; off a root its child is only pushed over the current screen, and a child-less tab
 * link is dropped.
 */
fun NavController.deliver(target: RouteTarget) {
    when (target) {
        is RouteTarget.Push -> push(target.route)
        is RouteTarget.Tab -> when {
            atRoot() && target.fresh -> openTabFresh(target.tab, target.child)
            atRoot() -> {
                selectTab(target.tab)
                target.child?.let { push(it) }
            }
            target.child != null -> push(target.child)
            else -> Timber.tag(TAG).w("Dropped a tab link off a tab root (#1447)")
        }
    }
}
