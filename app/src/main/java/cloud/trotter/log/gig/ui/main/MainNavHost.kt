package cloud.trotter.log.gig.ui.main

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import cloud.trotter.log.gig.core.designsystem.motion.rememberReducedMotion
import cloud.trotter.log.gig.feature.settings.CapabilityConsentScreen
import cloud.trotter.log.gig.feature.settings.CensusScreen
import cloud.trotter.log.gig.feature.settings.CensusSentScreen
import cloud.trotter.log.gig.feature.settings.ContributorScreen
import cloud.trotter.log.gig.feature.settings.DataExportScreen
import cloud.trotter.log.gig.feature.settings.DeveloperSettingsScreen
import cloud.trotter.log.gig.feature.settings.EvidenceSettingsScreen
import cloud.trotter.log.gig.feature.settings.GeneralSettingsScreen
import cloud.trotter.log.gig.feature.settings.PlatformSettingsScreen
import cloud.trotter.log.gig.feature.settings.PrivacyScreen
import cloud.trotter.log.gig.feature.settings.StrategySettingsScreen
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsScreen
import cloud.trotter.log.gig.ui.main.analytics.SessionDetailScreen
import cloud.trotter.log.gig.ui.main.analytics.day.DayScreen
import cloud.trotter.log.gig.ui.main.analytics.month.MonthScreen
import cloud.trotter.log.gig.ui.main.analytics.search.SearchScreen
import cloud.trotter.log.gig.ui.main.analytics.shifts.ShiftsScreen
import cloud.trotter.log.gig.ui.main.dashboard.DashboardScreen
import cloud.trotter.log.gig.ui.main.navigation.MainNavActions
import cloud.trotter.log.gig.ui.main.navigation.Screen
import cloud.trotter.log.gig.ui.main.navigation.ScreenTransition
import cloud.trotter.log.gig.ui.main.navigation.enter
import cloud.trotter.log.gig.ui.main.navigation.exit
import cloud.trotter.log.gig.ui.main.navigation.mainGraph
import cloud.trotter.log.gig.ui.main.navigation.screenTransition
import cloud.trotter.log.gig.ui.main.plan.WeeklyPlanScreen
import cloud.trotter.log.gig.ui.main.playbook.PlaybookScreen
import cloud.trotter.log.gig.ui.main.ratings.RatingsScreen
import cloud.trotter.log.gig.ui.main.settings.AboutScreen
import cloud.trotter.log.gig.ui.main.settings.EconomySettingsScreen
import cloud.trotter.log.gig.ui.main.settings.SettingsHomeScreen
import cloud.trotter.log.gig.ui.main.settings.garage.GarageScreen
import cloud.trotter.log.gig.ui.main.settings.garage.VehicleAddScreen
import cloud.trotter.log.gig.ui.main.settings.garage.VehicleDetailsScreen
import cloud.trotter.log.gig.ui.main.setup.wizard.WizardScreen

/**
 * The app's NavHost (#1447, moved out of `MainActivity`): the one flat [mainGraph], start destination
 * Home, each screen wired only to [MainNavActions]. Route changes animate through [screenTransition] (#1524).
 */
@Composable
fun MainNavHost(navController: NavHostController, actions: MainNavActions, modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotion()
    fun AnimatedContentTransitionScope<NavBackStackEntry>.transition(isPop: Boolean): ScreenTransition =
        screenTransition(initialState.destination.route, targetState.destination.route, isPop, reducedMotion)
    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route,
        modifier = modifier,
        enterTransition = { transition(isPop = false).enter() },
        exitTransition = { transition(isPop = false).exit() },
        popEnterTransition = { transition(isPop = true).enter() },
        popExitTransition = { transition(isPop = true).exit() },
    ) {
        mainGraph { route, _ -> MainScreen(route, actions) }
    }
}

/** The real screen for [route]. */
@Composable
private fun MainScreen(route: String, actions: MainNavActions) {
    when (route) {
        // --- The three tabs ---
        Screen.Dashboard.route -> DashboardScreen(
            onOpenAnalytics = actions::openRecap,
            onOpenPlan = actions::openPlan,
            onOpenRatings = actions::openRatings,
            onOpenSettings = actions::openSettings,
            onOpenWizard = actions::openWizard,
            onOpenPrivacy = actions::openPrivacy,
        )
        Screen.Analytics.route -> AnalyticsScreen(
            onOpenSettings = actions::openSettings,
            onOpenSession = actions::openSession,
            onOpenDay = actions::openDay,
            onOpenShifts = actions::openShifts,
            onOpenMonth = actions::openMonth,
            onOpenSearch = { actions.openSearch() },
        )
        Screen.Playbook.route -> PlaybookScreen(
            onOpenSettings = actions::openSettings,
            onOpenPlan = actions::openPlaybookPlan,
        )

        // --- Below a tab ---
        Screen.Ratings.route -> RatingsScreen(onBack = actions::back)
        Screen.WeeklyPlan.route -> WeeklyPlanScreen(onBack = actions::back)
        Screen.SessionDetail.route -> SessionDetailScreen(onBack = actions::back, onSearchAll = actions::openSearch)
        Screen.Day.route -> DayScreen(onBack = actions::back, onOpenSession = { actions.openSession(it) })
        Screen.Shifts.route -> ShiftsScreen(
            onBack = actions::back,
            onOpenSession = actions::openSession,
            onOpenSearch = { actions.openSearch() },
        )
        Screen.Search.route -> SearchScreen(onBack = actions::back, onOpenSession = actions::openSession)
        Screen.Month.route -> MonthScreen(onBack = actions::back, onOpenRecapWeek = actions::backToRecap)
        Screen.Wizard.route -> WizardScreen(onComplete = actions::back, onExit = actions::back)

        // --- Settings ---
        Screen.SettingsHome.route -> SettingsHomeScreen(onNavigate = actions::open, onBack = actions::back)
        Screen.AboutSettings.route -> AboutScreen(onBack = actions::back)
        Screen.StrategySettings.route -> StrategySettingsScreen(onBack = actions::back)
        Screen.EvidenceSettings.route -> EvidenceSettingsScreen(onBack = actions::back)
        Screen.DataExport.route -> DataExportScreen(onBack = actions::back)
        Screen.PrivacySettings.route -> PrivacyScreen(onBack = actions::back)
        Screen.CensusSettings.route -> CensusScreen(
            onBack = actions::back,
            onOpenSent = { actions.open(Screen.CensusSent.route) },
            onOpenReviewer = { actions.open(Screen.CensusReviewer.route) },
        )
        Screen.CensusSent.route -> CensusSentScreen(onBack = actions::back)
        Screen.CensusReviewer.route -> ContributorScreen(onBack = actions::back)
        Screen.ConsentSettings.route -> CapabilityConsentScreen(
            onBack = actions::back,
            onOpenPrivacy = actions::openPrivacy,
        )
        Screen.EconomySettings.route -> EconomySettingsScreen(
            onBack = actions::back,
            onOpenGarage = { actions.open(Screen.Garage.route) },
            onOpenVehicle = actions::openVehicle,
        )
        Screen.Garage.route -> GarageScreen(
            onBack = actions::back,
            onAdd = { actions.open(Screen.VehicleAdd.route) },
            onEdit = actions::openVehicle,
        )
        Screen.VehicleAdd.route -> VehicleAddScreen(onDone = actions::back)
        Screen.VehicleEdit.route -> VehicleDetailsScreen(
            onDone = actions::back,
            onAddVehicle = { actions.open(Screen.VehicleAdd.route) },
        )
        Screen.GeneralSettings.route -> GeneralSettingsScreen(onBack = actions::back)
        Screen.DeveloperSettings.route -> DeveloperSettingsScreen(onBack = actions::back)
        Screen.PlatformSettings.route -> PlatformSettingsScreen(onBack = actions::back)
    }
}
