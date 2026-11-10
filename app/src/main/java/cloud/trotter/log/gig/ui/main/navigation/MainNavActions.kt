package cloud.trotter.log.gig.ui.main.navigation

import androidx.navigation.NavController
import cloud.trotter.log.gig.domain.analytics.recap.RecapMeasure
import java.time.LocalDate
import java.time.YearMonth

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

    /** #170: the privacy links on the screen reading card and Automation & consent: gig.log's Privacy page. */
    fun openPrivacy() = nav.push(Screen.PrivacySettings.route)
    /** A shift's page; [row] lands it on a timeline item (Recap's Shifts to check). */
    fun openSession(sessionId: String, row: String? = null) = nav.navigate(Screen.SessionDetail.route(sessionId, row))

    /** Recap's day with two or more shifts. */
    fun openDay(date: LocalDate) = nav.navigate(Screen.Day.route(date))

    /** Recap's Shifts row: every shift, opened at the Monday [weekStart]. */
    fun openShifts(weekStart: LocalDate) = nav.navigate(Screen.Shifts.route(weekStart))

    /** Recap's date picker, Month: [month] on Recap's stack, opening on Recap's switch value. */
    fun openMonth(month: YearMonth, measure: RecapMeasure) = nav.navigate(Screen.Month.route(month, measure))

    /** Search all shifts by store (Recap, the Shifts list, a shift page's "Search all shifts for …"), with [query]. */
    fun openSearch(query: String? = null) = nav.navigate(Screen.Search.route(query))

    /** A Month row: back to Recap under it. Nothing moves when Recap is not on the stack. */
    fun backToRecap() {
        nav.popBackStack(Screen.Analytics.route, inclusive = false)
    }

    /** A static route pushed over the current screen (the Settings menu, Garage, Costs). */
    fun open(route: String) = nav.push(route)

    fun openVehicle(vehicleId: String) = nav.navigate(Screen.VehicleEdit.route(vehicleId))

    fun back() {
        nav.popBackStack()
    }
}
