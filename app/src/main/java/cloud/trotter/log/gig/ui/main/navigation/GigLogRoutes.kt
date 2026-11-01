package cloud.trotter.log.gig.ui.main.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    // Top Level
    data object Dashboard : Screen("dashboard")
    data object Wizard : Screen("wizard")

    // Ratings (epic #320): a row on Home since #1447.
    data object Ratings : Screen("ratings")
    // Analytics hub (#315 H1) — Money tab v1; Patterns/Decisions/Time tabs stubbed.
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
     * Per-dash drill-down (#650) — the read-only session detail for one dash. The [route] template
     * carries the sessionId; [route] (the fun) URL-encodes it since a session id can hold arbitrary
     * characters. The screen reads the id back from `SavedStateHandle`.
     */
    data object SessionDetail : Screen("analytics/session/{sessionId}") {
        const val ARG_SESSION_ID = "sessionId"
        fun route(sessionId: String): String = "analytics/session/${Uri.encode(sessionId)}"
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
         * the routes no external link may target — the wizard, Ratings and the two argument routes.
         */
        val graphRoutes: Set<String> by lazy {
            allRoutes + setOf(Wizard.route, Ratings.route, SessionDetail.route, VehicleEdit.route)
        }
    }
}