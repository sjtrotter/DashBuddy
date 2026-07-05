package cloud.trotter.dashbuddy.ui.main.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    // Top Level
    data object Dashboard : Screen("dashboard")
    data object Wizard : Screen("wizard")

    // Analytics surfaces (home-screen entry tiles, epic #320)
    data object Ratings : Screen("ratings")
    // Analytics hub (#315 H1) — Money tab v1; Patterns/Decisions/Time tabs stubbed.
    data object Analytics : Screen("analytics")

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
    data object EconomySettings : Screen("settings/economy")
    data object GeneralSettings : Screen("settings/general")
    data object DeveloperSettings : Screen("settings/developer")
    data object PlatformSettings : Screen("settings/platforms")
}