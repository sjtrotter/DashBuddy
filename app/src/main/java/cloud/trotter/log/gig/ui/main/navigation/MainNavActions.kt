package cloud.trotter.log.gig.ui.main.navigation

import androidx.navigation.NavController
import java.time.LocalDate

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
    /** A shift's page; [row] lands it on a timeline item (Recap's Shifts to check). */
    fun openSession(sessionId: String, row: String? = null) = nav.navigate(Screen.SessionDetail.route(sessionId, row))

    /** Recap's day with two or more shifts. */
    fun openDay(date: LocalDate) = nav.navigate(Screen.Day.route(date))

    /** Recap's Shifts row: every shift, opened at the Monday [weekStart]. */
    fun openShifts(weekStart: LocalDate) = nav.navigate(Screen.Shifts.route(weekStart))

    /** A static route pushed over the current screen (the Settings menu, Garage, Costs). */
    fun open(route: String) = nav.push(route)

    fun openVehicle(vehicleId: String) = nav.navigate(Screen.VehicleEdit.route(vehicleId))

    fun back() {
        nav.popBackStack()
    }
}
