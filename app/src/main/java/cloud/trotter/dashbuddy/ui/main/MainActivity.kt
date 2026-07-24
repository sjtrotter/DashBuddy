package cloud.trotter.dashbuddy.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cloud.trotter.dashbuddy.ui.main.analytics.AnalyticsScreen
import cloud.trotter.dashbuddy.ui.main.analytics.SessionDetailScreen
import cloud.trotter.dashbuddy.ui.main.dashboard.DashboardScreen
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import timber.log.Timber
import cloud.trotter.dashbuddy.ui.main.ratings.RatingsScreen
import cloud.trotter.dashbuddy.ui.main.settings.AboutScreen
import cloud.trotter.dashbuddy.ui.main.settings.EconomySettingsScreen
import cloud.trotter.dashbuddy.feature.settings.CapabilityConsentScreen
import cloud.trotter.dashbuddy.feature.settings.DataExportScreen
import cloud.trotter.dashbuddy.feature.settings.EvidenceSettingsScreen
import cloud.trotter.dashbuddy.feature.settings.GeneralSettingsScreen
import cloud.trotter.dashbuddy.feature.settings.PlatformSettingsScreen
import cloud.trotter.dashbuddy.ui.main.settings.SettingsHomeScreen
import cloud.trotter.dashbuddy.feature.settings.StrategySettingsScreen
import cloud.trotter.dashbuddy.ui.main.setup.wizard.WizardScreen
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Deep-link target route pushed by an external launcher (the bubble's "Vehicle" just-in-time
     * action, #693). Held as a one-shot: the NavHost consumes it once and clears it, so a
     * config-change recomposition doesn't re-navigate. `onNewIntent` re-arms it when an already-open
     * instance is brought forward.
     */
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Consume-once (#693 review F2b): getIntent() is sticky across recreation — without
        // removing the extra, a rotation re-reads it in the new instance's onCreate and pushes a
        // duplicate destination onto the restored back stack.
        pendingRoute.value = intent?.getStringExtra(EXTRA_ROUTE)
        intent?.removeExtra(EXTRA_ROUTE)

        setContent {
            DashBuddyTheme {
                val navController = rememberNavController()

                // Consume a deep-link route once, then clear it (#693 vehicle action).
                val route by pendingRoute.collectAsStateWithLifecycle()
                LaunchedEffect(route) {
                    route?.let {
                        // #693 review F3: MainActivity is exported (launcher) — a forged extra
                        // carrying a non-route string would crash navigate() with
                        // IllegalArgumentException. Fail closed: navigate only to known routes.
                        if (it in Screen.allRoutes) navController.navigate(it)
                        else Timber.tag("Main").w("Dropped unknown deep-link route (#693)")
                        pendingRoute.value = null
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Dashboard.route,
                        modifier = Modifier.padding(innerPadding)
                    ) {

                        // --- MAIN DASHBOARD ---
                        composable(Screen.Dashboard.route) {
                            DashboardScreen(
                                onNavigate = { route -> navController.navigate(route) },
                                onNavigateToSettings = {
                                    navController.navigate(Screen.SettingsHome.route)
                                },
                                onNavigateToWizard = { // <-- UPDATED CALLBACK
                                    navController.navigate(Screen.Wizard.route)
                                }
                            )
                        }

                        // --- RATINGS (#316) ---
                        composable(Screen.Ratings.route) {
                            RatingsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // --- ANALYTICS HUB (#315 H1 — Money tab v1) ---
                        composable(Screen.Analytics.route) {
                            AnalyticsScreen(
                                onBack = { navController.popBackStack() },
                                onExportCsv = { navController.navigate(Screen.DataExport.route) },
                                onOpenSession = { sessionId ->
                                    navController.navigate(Screen.SessionDetail.route(sessionId))
                                }
                            )
                        }

                        // --- PER-DASH DRILL-DOWN (#650 — read-only session detail) ---
                        composable(
                            Screen.SessionDetail.route,
                            arguments = listOf(
                                navArgument(Screen.SessionDetail.ARG_SESSION_ID) {
                                    type = NavType.StringType
                                }
                            )
                        ) {
                            SessionDetailScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // --- INTERACTIVE WIZARD FLOW ---
                        composable(Screen.Wizard.route) {
                            WizardScreen(
                                onComplete = {
                                    // Pops the wizard off the stack, returning to the Dashboard
                                    navController.popBackStack()
                                }
                            )
                        }

                        // ========================================================
                        // SETTINGS HIERARCHY
                        // ========================================================

                        // 1. Settings Home (The Menu)
                        composable(Screen.SettingsHome.route) {
                            SettingsHomeScreen(
                                onNavigate = { route -> navController.navigate(route) },
                                onBack = { navController.popBackStack() }
                            )
                        }
                        // 1.1. About screen
                        composable(Screen.AboutSettings.route) {
                            AboutScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // 2. Strategy (The Visualizer)
                        composable(Screen.StrategySettings.route) {
                            StrategySettingsScreen()
                        }

                        // 3. Evidence Locker
                        composable(Screen.EvidenceSettings.route) {
                            EvidenceSettingsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // 3a. Export Data (CSV, #319)
                        composable(Screen.DataExport.route) {
                            DataExportScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // 3c. Automation & Consent (capability grants, #422)
                        composable(Screen.ConsentSettings.route) {
                            CapabilityConsentScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // 3b. Personal Economy (operating costs, #145)
                        composable(Screen.EconomySettings.route) {
                            EconomySettingsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // 4. General Settings
                        composable(Screen.GeneralSettings.route) {
                            GeneralSettingsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // 5. Developer Options
                        composable(Screen.DeveloperSettings.route) {
                            PlaceholderScreen(
                                title = stringResource(R.string.main_activity_developer_options_title),
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // 6. Platform / Gig Apps
                        composable(Screen.PlatformSettings.route) {
                            PlatformSettingsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute.value = intent.getStringExtra(EXTRA_ROUTE)
        intent.removeExtra(EXTRA_ROUTE)
    }

    companion object {
        /** Extra carrying a [Screen.route] to open on launch (the bubble's deep-link actions, #693). */
        const val EXTRA_ROUTE = "cloud.trotter.dashbuddy.extra.ROUTE"

        /**
         * Build an [Intent] that opens [MainActivity] on [route]. Used by the bubble overlay (a
         * separate task) to deep-link into settings, so it carries [Intent.FLAG_ACTIVITY_NEW_TASK].
         */
        fun routeIntent(context: Context, route: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                // SINGLE_TOP (#693 review F2a): without it, standard-launchMode + CLEAR_TOP
                // DESTROYS an open MainActivity and delivers to a fresh instance's onCreate —
                // onNewIntent never fires and the user's back stack is lost on every Vehicle tap.
                // With it, the single-activity app's task-top instance receives onNewIntent live.
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(EXTRA_ROUTE, route)
            }
    }
}

/**
 * A temporary placeholder to prevent compile errors for screens
 * you haven't built yet (General & Developer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(title: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.main_activity_placeholder_construction),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}