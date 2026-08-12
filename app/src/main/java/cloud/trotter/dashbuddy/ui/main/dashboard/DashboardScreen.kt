package cloud.trotter.dashbuddy.ui.main.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.dashboard.components.DashingStatusRow
import cloud.trotter.dashbuddy.feature.dashboard.components.EntryTile
import cloud.trotter.dashbuddy.feature.dashboard.components.StatusCard
import cloud.trotter.dashbuddy.feature.dashboard.components.TodayCard
import cloud.trotter.dashbuddy.feature.dashboard.components.TodayHeader
import cloud.trotter.dashbuddy.feature.dashboard.components.WeekCard
import cloud.trotter.dashbuddy.ui.components.HowNumbersWorkFooter
import cloud.trotter.dashbuddy.ui.main.analytics.ReviewItem
import cloud.trotter.dashbuddy.ui.main.analytics.reviewItems
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import cloud.trotter.dashbuddy.ui.main.setup.consent.ConsentPromptSheet
import cloud.trotter.dashbuddy.ui.main.setup.permissions.PermissionsBottomSheet
import cloud.trotter.dashbuddy.util.PermissionUtils
import kotlinx.coroutines.launch

/**
 * Home — **"Today"** since #977, decluttered to **four blocks** by #1024 section D.
 *
 * Top to bottom: header (date · live clock · status pill) · **Today** (kept, the four supporting
 * figures, a hairline, then today's plan strip) · **This week** (net + delta + sparkline + `Recap →`,
 * the weekly-plan pointer, the review items — three hairline rows of one card) · one row of four
 * entry tiles (Analytics · Playbook · Ratings · Settings) · the shared **"How these numbers work"**
 * footer, the screen's single disclosure affordance (#1024 rule 2).
 *
 * Read-side only — nothing here writes to the state machine, and #1024 D added no read: the merge is
 * subtraction and re-composition of surfaces that already shipped.
 *
 * The host stays in `:app` (nav start destination, the `Screen` route table, the `:app`-owned
 * permission/consent sheets); the presentational blocks live in `:feature:dashboard`. The one
 * deliberate exception is the review rows, which are driven by the analytics hub's own
 * [reviewItems] — the flag rules and their copy have one owner in `:app`, and duplicating them into a
 * feature module to satisfy placement would be exactly the divergence Principle 5 exists to prevent.
 * They are handed to [WeekCard] as a slot, which is what lets the row sit inside the week card
 * without the feature module ever seeing an `:app` type.
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

    Scaffold { padding ->
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

                // CASE 3: Ready — the "Today" screen (brief §2, decluttered by #1024 D).
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

                    // BLOCK 1 — today: what it kept, and what is still worth working (D1).
                    TodayCard(
                        economics = uiState.todayEconomics,
                        today = uiState.today,
                        heatmap = uiState.heatmap,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // BLOCK 2 — the week: recap, plan pointer, review items (D4). The plan row
                    // renders only with a saved plan and the review row only with a raised flag —
                    // both are decided by the data, not by a placeholder.
                    WeekCard(
                        economics = uiState.weekEconomics,
                        previousEconomics = uiState.previousWeekEconomics,
                        dailyEarnings = uiState.weekDailyEarnings,
                        plan = uiState.weeklyPlan,
                        onOpenRecap = openWeekRecap,
                        onOpenPlan = { onNavigate(Screen.WeeklyPlan.route) },
                        reviewContent = reviewSlot(uiState, openWeekRecap),
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // BLOCK 3 — the app's destinations.
                    EntryTileRow(
                        onNavigate = onNavigate,
                        onNavigateToSettings = onNavigateToSettings,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.showWelcomeBubble() }
                    ) { Text(stringResource(R.string.dashboard_screen_show_bubble_button)) }

                    // BLOCK 4 — the screen's one disclosure (#1024 rule 2).
                    Spacer(modifier = Modifier.height(20.dp))
                    HowNumbersWorkFooter()
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * The week's data-quality chores as the week card's third row (#1024 D4) — or `null` when the week is
 * clean, which is what keeps the hairline from hanging over an empty section.
 *
 * The flags, their thresholds and their wording stay owned by the analytics hub's [reviewItems]
 * (Principle 5 — Home re-implements none of it). What Home changes is only the affordance: the hub's
 * rows open the assign/attest dialogs in place, which live on the Money tab together with the orphan
 * lists they mutate. Home has no standing to host those flows, so the whole section carries ONE
 * honest affordance — open the place that owns the fix — rather than per-row labels promising an
 * action this screen cannot perform.
 */
@Composable
private fun reviewSlot(uiState: DashboardUiState, onOpenInAnalytics: () -> Unit): (@Composable () -> Unit)? {
    val items = reviewItems(
        economics = uiState.weekEconomics,
        orphanOfferGroups = uiState.orphanOfferGroups,
        onOpenNoSession = onOpenInAnalytics,
        onOpenOrphanOffers = onOpenInAnalytics,
    )
    if (items.isEmpty()) return null
    return { ReviewRow(items = items, onOpen = onOpenInAnalytics) }
}

/** `NEEDS A LOOK (2)` over the flags' own sentences, with one `Review →` for the row. */
@Composable
private fun ReviewRow(items: List<ReviewItem>, onOpen: () -> Unit) {
    val c = AppTheme.colors
    val label = stringResource(R.string.dashboard_review_action)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = label, role = Role.Button) { onOpen() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    R.string.money_tab_needs_a_look_title_format,
                    Formats.commaInt(items.size),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = c.warn,
            )
            items.forEach { item ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodySmall,
                    // An over-count is the stronger signal — the tone the hub's card gives it too.
                    color = if (item.severe) c.bad else c.text2,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = c.accent)
    }
}

/**
 * The app's four destinations in one row (#1024 D5): Analytics · Playbook · Ratings · Settings.
 *
 * Settings moved off the floating action button and into the row so every destination is reached the
 * same way — a FAB beside four tiles was a fifth affordance for a peer of the other four. Strategy and
 * Economy lost their tiles: both are settings pages, both are one tap inside Settings, and Home is a
 * glance surface, not a menu of every route.
 */
@Composable
private fun EntryTileRow(onNavigate: (String) -> Unit, onNavigateToSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EntryTile(
            icon = Icons.Filled.BarChart,
            label = stringResource(R.string.dashboard_screen_entry_analytics),
            modifier = Modifier.weight(1f),
            onClick = { onNavigate(Screen.Analytics.route) },
        )
        EntryTile(
            icon = Icons.Filled.Map,
            label = stringResource(R.string.dashboard_screen_entry_playbook),
            modifier = Modifier.weight(1f),
            onClick = { onNavigate(Screen.Playbook.route) },
        )
        EntryTile(
            icon = Icons.Filled.Star,
            label = stringResource(R.string.dashboard_screen_entry_ratings),
            modifier = Modifier.weight(1f),
            onClick = { onNavigate(Screen.Ratings.route) },
        )
        EntryTile(
            icon = Icons.Filled.Settings,
            label = stringResource(R.string.dashboard_screen_entry_settings),
            modifier = Modifier.weight(1f),
            onClick = onNavigateToSettings,
        )
    }
}
