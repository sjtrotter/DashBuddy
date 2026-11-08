package cloud.trotter.log.gig.ui.main.navigation

import android.net.Uri
import java.time.LocalDate

sealed class Screen(val route: String) {
    // Top Level
    data object Dashboard : Screen("dashboard")
    data object Wizard : Screen("wizard")

    // Ratings (epic #320): a row on Home since #1447.
    data object Ratings : Screen("ratings")
    // Recap (#1515): the week. The route keeps its #315 name.
    data object Analytics : Screen("analytics")

    /**
     * Weekly Plan (#981, redesign stage 6) — reached from the Sunday-evening notification's deep link,
     * Home's pointer row and the Playbook's plan card; it sits on the Playbook tab's stack (#1447). Static
     * route: it takes no arguments, which is what lets it sit in [allRoutes] and be a legal deep-link target.
     */
    data object WeeklyPlan : Screen("plan/week")

    /**
     * Playbook (#1024 section C) — *the next move*, beside Home (*today*) and Analytics (*the past*).
     * Holds this week's plan progress, the lifetime heatmap and the store leaderboard: surfaces that
     * are lifetime- or week-scoped by definition and therefore never belonged inside a period-first
     * hub. Static route, so it is a legal deep-link target like the others.
     */
    data object Playbook : Screen("playbook")

    /**
     * The shift page: one shift's money and timeline. The [route] template carries the sessionId and an
     * optional `row`, a timeline item key to land on (search and Recap's links); [route] (the fun) URL-encodes
     * both, since a session id can hold arbitrary characters. The screen reads them back from `SavedStateHandle`.
     */
    data object SessionDetail : Screen("analytics/session/{sessionId}?row={row}") {
        const val ARG_SESSION_ID = "sessionId"
        const val ARG_ROW = "row"
        fun route(sessionId: String, row: String? = null): String =
            "analytics/session/${Uri.encode(sessionId)}" + (row?.let { "?row=${Uri.encode(it)}" } ?: "")
    }

    /**
     * The day page (#1515): a day with two or more shifts, opened from Recap's chart; it sits on Recap's stack. The
     * argument is the local date in ISO form ("2026-10-28").
     */
    data object Day : Screen("analytics/day/{date}") {
        const val ARG_DATE = "date"
        fun route(date: LocalDate): String = "analytics/day/$date"
    }

    /**
     * The Shifts list (#1515): every shift, newest first, by week, opened from Recap's Shifts row; it sits on Recap's
     * stack. The optional `week` is the ISO Monday it opens at ("2026-10-26"); absent, this week.
     */
    data object Shifts : Screen("analytics/shifts?week={week}") {
        const val ARG_WEEK = "week"
        fun route(weekStart: LocalDate? = null): String = "analytics/shifts" + (weekStart?.let { "?week=$it" } ?: "")
    }

    // Settings Hierarchy
    // Note: We use "settings/home" as the landing page for settings
    data object SettingsHome : Screen("settings/home")

    // Sub-menus
    data object AboutSettings : Screen("settings/about")
    data object StrategySettings : Screen("settings/strategy")
    data object EvidenceSettings : Screen("settings/evidence")
    data object DataExport : Screen("settings/data-export")
    data object ConsentSettings : Screen("settings/consent")
    /** #1457 — the in-app privacy page: what gig.log reads, saves and sends. */
    data object PrivacySettings : Screen("settings/privacy")
    data object Garage : Screen("settings/garage")
    data object VehicleAdd : Screen("settings/garage/add")
    data object VehicleEdit : Screen("settings/garage/vehicle/{vehicleId}") {
        fun route(vehicleId: String) = "settings/garage/vehicle/$vehicleId"
        const val ARG = "vehicleId"
    }
    data object EconomySettings : Screen("settings/economy")
    data object GeneralSettings : Screen("settings/general")
    data object DeveloperSettings : Screen("settings/developer")
    data object PlatformSettings : Screen("settings/platforms")

    companion object {
        /**
         * The static routes an external deep link (MainActivity.EXTRA_ROUTE, #693) may target —
         * the fail-closed allowlist for the exported-activity seam. Parameterized routes
         * (e.g. [SessionDetail]) are deliberately excluded: deep links carry no args.
         */
        // lazy: a plain initializer runs during Screen's class-init, BEFORE the nested data
        // objects exist (touching any Screen.X triggers it) → ExceptionInInitializerError.
        val allRoutes: Set<String> by lazy { setOf(
            Dashboard.route, Analytics.route, WeeklyPlan.route, Playbook.route,
            SettingsHome.route, AboutSettings.route,
            StrategySettings.route, EvidenceSettings.route, DataExport.route,
            ConsentSettings.route, PrivacySettings.route,
            Garage.route, VehicleAdd.route,
            EconomySettings.route, GeneralSettings.route, DeveloperSettings.route,
            PlatformSettings.route,
        ) }

        /**
         * Every route the one flat graph registers (#1447 `mainGraph`): the deep-link allowlist plus
         * the routes no external link may target — the wizard, Ratings and the argument routes.
         */
        val graphRoutes: Set<String> by lazy {
            allRoutes + setOf(Wizard.route, Ratings.route, SessionDetail.route, Day.route, Shifts.route, VehicleEdit.route)
        }
    }
}