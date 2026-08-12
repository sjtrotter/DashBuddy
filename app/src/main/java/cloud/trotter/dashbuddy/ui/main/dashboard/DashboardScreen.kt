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
import androidx.compose.material.icons.filled.Map
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
import androidx.compose.runtime.rememberCoroutineScope
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
import cloud.trotter.dashbuddy.feature.dashboard.components.SoFarToday
import cloud.trotter.dashbuddy.feature.dashboard.components.StatusCard
import cloud.trotter.dashbuddy.feature.dashboard.components.TodayHeader
import cloud.trotter.dashbuddy.feature.dashboard.components.TodayPlanCard
import cloud.trotter.dashbuddy.feature.dashboard.components.WeekRecapCard
import cloud.trotter.dashbuddy.feature.dashboard.components.WeeklyPlanPointerRow
import cloud.trotter.dashbuddy.ui.main.analytics.NeedsALookCard
import cloud.trotter.dashbuddy.ui.main.analytics.ReviewAction
import cloud.trotter.dashbuddy.ui.main.analytics.reviewItems
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import cloud.trotter.dashbuddy.ui.main.setup.consent.ConsentPromptSheet
import cloud.trotter.dashbuddy.ui.main.setup.permissions.PermissionsBottomSheet
import cloud.trotter.dashbuddy.util.PermissionUtils
import kotlinx.coroutines.launch

/**
 * Home — **"Today"** since #977 (redesign stage 4 of #969, brief §2).
 *
 * Top to bottom: header (date · live clock · status pill) · **Today's plan** (the driver's own
 * weekday history) · **So far today** · **This week** (net, delta, sparkline, `Recap →`) · review
 * items · entry tiles + Show bubble. Read-side only — nothing here writes to the state machine.
 *
 * The host stays in `:app` (nav start destination, the `Screen` route table, the `:app`-owned
 * permission/consent sheets); the presentational blocks live in `:feature:dashboard`. The one
 * deliberate exception is the review-items card, which is the analytics hub's own
 * [NeedsALookCard]/[reviewItems] pair reused verbatim — the flag rules and their copy have one owner
 * in `:app`, and duplicating them into a feature module to satisfy placement would be exactly the
 * divergence Principle 5 exists to prevent.
 */
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onNavigate: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToWizard: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // `Recap →` and every review-item action land on the same place: the Analytics hub, anchored on
    // the week Home was just describing. The selection write is ordered BEFORE the navigation so the
    // hub opens already on that window rather than flickering through its last one.
    val openWeekRecap: () -> Unit = {
        scope.launch {
            viewModel.selectWeekRecapWindow()
            onNavigate(Screen.Analytics.route)
        }
    }

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

                // CASE 3: Ready — the "Today" screen (brief §2).
                else -> {
                    TodayHeader(
                        today = uiState.today,
                        statusText = stringResource(uiState.statusText),
                        dashing = uiState.isDashing,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (uiState.isDashing) {
                        DashingStatusRow(onTap = { viewModel.showWelcomeBubble() })
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    TodayPlanCard(today = uiState.today, heatmap = uiState.heatmap)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Weekly-plan pointer (brief §2 row 3, #981) — rendered ONLY when the driver
                    // has saved a plan for the week they are in. No plan, no row: an empty
                    // placeholder would advertise a screen they have not asked for.
                    uiState.weeklyPlan?.let { plan ->
                        WeeklyPlanPointerRow(
                            plan = plan,
                            onOpen = { onNavigate(Screen.WeeklyPlan.route) },
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    SoFarToday(economics = uiState.todayEconomics)
                    Spacer(modifier = Modifier.height(16.dp))

                    WeekRecapCard(
                        economics = uiState.weekEconomics,
                        previousEconomics = uiState.previousWeekEconomics,
                        dailyEarnings = uiState.weekDailyEarnings,
                        onOpenRecap = openWeekRecap,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    ReviewItemsRow(
                        uiState = uiState,
                        onOpenInAnalytics = openWeekRecap,
                    )

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

/**
 * The week's data-quality chores (brief §2 row 6), reusing the analytics hub's own flag rules and
 * copy ([reviewItems]) and its consolidated card ([NeedsALookCard]) — no second threshold, no second
 * wording.
 *
 * Only the ACTION differs: the hub's rows open the assign/attest dialogs in place, which live on the
 * Money tab together with the orphan lists they mutate. Home has no standing to host those flows, so
 * every row here carries the same honest affordance — open the place that owns the fix. The label is
 * therefore normalised rather than inherited: a row promising "Assign to a dash" that actually
 * navigates would be a small lie.
 *
 * Renders nothing at all when the week is clean (the card self-gates on an empty list).
 */
@Composable
private fun ReviewItemsRow(uiState: DashboardUiState, onOpenInAnalytics: () -> Unit) {
    val label = stringResource(R.string.dashboard_review_action)
    val items = reviewItems(
        economics = uiState.weekEconomics,
        orphanOfferGroups = uiState.orphanOfferGroups,
        onOpenNoSession = onOpenInAnalytics,
        onOpenOrphanOffers = onOpenInAnalytics,
    ).map { it.copy(action = ReviewAction(label = label, onClick = onOpenInAnalytics)) }

    if (items.isEmpty()) return
    NeedsALookCard(items = items)
    Spacer(modifier = Modifier.height(16.dp))
}

/**
 * Entry tiles: Analytics · Ratings · Strategy · Economy, plus **Playbook** (#1024 part 1 — the new
 * destination has to be reachable, and Home's tile row is where the app's destinations live).
 *
 * Deliberately a minimal addition — #1024 section D restyles this whole block into a single row of
 * four, so anything more here would be work done twice.
 */
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EntryTile(
                icon = Icons.Filled.Map,
                label = stringResource(R.string.dashboard_screen_entry_playbook),
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Screen.Playbook.route) },
            )
            // Keeps the tile the same width as the four above it rather than stretching it across the
            // row; the odd tile out disappears when #1024 section D reflows this into one row of four.
            Spacer(Modifier.weight(1f))
        }
    }
}
