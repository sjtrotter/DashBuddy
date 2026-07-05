package cloud.trotter.dashbuddy.ui.main.navigation

sealed class Screen(val route: String) {
    // Top Level
    data object Dashboard : Screen("dashboard")
    data object Wizard : Screen("wizard")

    // Analytics surfaces (home-screen entry tiles, epic #320)
    data object Ratings : Screen("ratings")
    // Analytics hub (#315 H1) — Money tab v1; Patterns/Decisions/Time tabs stubbed.
    data object Analytics : Screen("analytics")

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