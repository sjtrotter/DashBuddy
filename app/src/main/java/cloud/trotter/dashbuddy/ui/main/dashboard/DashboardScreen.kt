package cloud.trotter.dashbuddy.ui.main.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.feature.dashboard.components.DashingStatusRow
import cloud.trotter.dashbuddy.feature.dashboard.components.EntryTile
import cloud.trotter.dashbuddy.feature.dashboard.components.PeriodReview
import cloud.trotter.dashbuddy.feature.dashboard.components.StatusCard
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import cloud.trotter.dashbuddy.ui.main.setup.consent.ConsentPromptSheet
import cloud.trotter.dashbuddy.ui.main.setup.permissions.PermissionsBottomSheet
import cloud.trotter.dashbuddy.util.PermissionUtils

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onNavigate: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToWizard: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Permissions are an OS-level fact re-checked on every resume — kept as
    // composable-local state, not in the UiState, since ON_RESUME owns the read.
    var hasPermissions by remember { mutableStateOf<Boolean?>(null) }
    var showPermissionSheet by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = PermissionUtils.hasAllEssentialPermissions(context)
        hasPermissions = granted
        if (!granted) {
            showPermissionSheet = true
        }
    }

    // ========================================================================
    // THE GATE: If permissions are missing, force the Bottom Sheet to appear
    // ========================================================================
    if (showPermissionSheet) {
        PermissionsBottomSheet(
            onAllGranted = { showPermissionSheet = false }
        )
    }

    // Prompted automation consent (#843): once essential permissions are in, the
    // app-foreground front door asks for per-capability automation consent — the
    // same rhythm the permission sheet uses. Self-gating: renders nothing when no
    // capability is undecided. Held back while the permission gate is up so the
    // two sheets never stack.
    if (hasPermissions == true && !showPermissionSheet) {
        ConsentPromptSheet()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.dashboard_screen_content_desc_settings))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .fillMaxSize()
        ) {
            when {
                // CASE 0: Loading — prevents the flicker.
                hasPermissions == null -> {
                    // Empty state while calculating.
                }

                // CASE 1: Permissions Missing (The Gate)
                hasPermissions == false -> {
                    StatusCard(
                        title = stringResource(R.string.dashboard_screen_permissions_required_title),
                        subtitle = stringResource(R.string.dashboard_screen_permissions_required_subtitle),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        textColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                // CASE 2: Permissions Granted, first run (The Guide)
                uiState.isFirstRun -> {
                    StatusCard(
                        title = stringResource(R.string.dashboard_screen_first_run_title),
                        subtitle = stringResource(R.string.dashboard_screen_first_run_subtitle),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavigateToWizard
                    ) { Text(stringResource(R.string.dashboard_screen_personalize_strategy_button)) }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.completeSetup() }
                    ) { Text(stringResource(R.string.dashboard_screen_skip_for_now_button)) }
                }

                // CASE 3: Ready — status card, a slim "tap for the bubble" pointer while
                // dashing, then the read-model period review (the primary economics), the
                // entry tiles, and a manual bubble trigger.
                else -> {
                    StatusCard(
                        title = stringResource(uiState.statusText),
                        subtitle = if (uiState.isDashing) stringResource(R.string.dashboard_screen_dashing_subtitle)
                        else stringResource(R.string.dashboard_screen_ready_subtitle),
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (uiState.isDashing) {
                        DashingStatusRow(onTap = { viewModel.showWelcomeBubble() })
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    PeriodReview(
                        selectedPeriod = uiState.selectedPeriod,
                        economics = uiState.economics,
                        onSelectPeriod = viewModel::setPeriod,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    EntryTileGrid(onNavigate = onNavigate)
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.showWelcomeBubble() }
                    ) { Text(stringResource(R.string.dashboard_screen_show_bubble_button)) }
                }
            }
        }
    }
}

/** 2×2 entry tiles: Analytics · Ratings · Strategy · Economy. */
@Composable
private fun EntryTileGrid(onNavigate: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EntryTile(
                icon = Icons.Filled.BarChart,
                label = stringResource(R.string.dashboard_screen_entry_analytics),
                modifier = Modifier.weight(1f),
                // #315 H1: routes to the Analytics hub (Money tab v1); other tabs stubbed.
                onClick = { onNavigate(Screen.Analytics.route) },
            )
            EntryTile(
                icon = Icons.Filled.Star,
                label = stringResource(R.string.dashboard_screen_entry_ratings),
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Screen.Ratings.route) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EntryTile(
                icon = Icons.Filled.Tune,
                label = stringResource(R.string.dashboard_screen_entry_strategy),
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Screen.StrategySettings.route) },
            )
            EntryTile(
                icon = Icons.Filled.AttachMoney,
                label = stringResource(R.string.dashboard_screen_entry_economy),
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Screen.EconomySettings.route) },
            )
        }
    }
}
