package cloud.trotter.dashbuddy.ui.navigation

sealed class Screen(val route: String) {
    data object Dashboard : Screen("dashboard")
    data object Settings : Screen("settings")
    data object StrategySettings : Screen("settings/strategy")
    // Add Evidence/Automation routes later
}