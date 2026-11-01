package cloud.trotter.log.gig.ui.main.navigation

import androidx.navigation.NavController

/**
 * One method per navigation intent (#1447). The NavHost's screens call only these, so how each intent
 * moves the stack has one owner.
 */
class MainNavActions(private val nav: NavController) {
    /** The bottom bar. */
    fun selectTab(tab: TopLevel) = nav.selectTab(tab)

    /** Home's `Recap →` and review row: Analytics, fresh. */
    fun openRecap() = nav.openTabFresh(TopLevel.ANALYTICS)

    /** Home's plan row: the plan in Playbook, fresh. */
    fun openPlan() = nav.deliver(RouteTargets.WeeklyPlan)

    /** Playbook's plan card: the plan over Playbook. */
    fun openPlaybookPlan() = nav.push(Screen.WeeklyPlan.route)

    fun openRatings() = nav.push(Screen.Ratings.route)
    fun openSettings() = nav.push(Screen.SettingsHome.route)
    fun openWizard() = nav.push(Screen.Wizard.route)
    fun openExport() = nav.push(Screen.DataExport.route)
    fun openSession(sessionId: String) = nav.navigate(Screen.SessionDetail.route(sessionId))

    /** A static route pushed over the current screen (the Settings menu, Garage, Costs). */
    fun open(route: String) = nav.push(route)

    fun openVehicle(vehicleId: String) = nav.navigate(Screen.VehicleEdit.route(vehicleId))

    fun back() {
        nav.popBackStack()
    }
}
