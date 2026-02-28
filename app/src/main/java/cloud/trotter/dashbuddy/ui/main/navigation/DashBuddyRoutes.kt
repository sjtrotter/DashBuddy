package cloud.trotter.dashbuddy.ui.main.navigation

sealed class Screen(val route: String) {
    // Top Level
    data object Dashboard : Screen("dashboard")
    data object Wizard : Screen("wizard")

    // Settings Hierarchy
    // Note: We use "settings/home" as the landing page for settings
    data object SettingsHome : Screen("settings/home")

    // Sub-menus
    data object AboutSettings : Screen("settings/about")
    data object StrategySettings : Screen("settings/strategy")
    data object EvidenceSettings : Screen("settings/evidence")
    data object GeneralSettings : Screen("settings/general")
    data object DeveloperSettings : Screen("settings/developer")
}