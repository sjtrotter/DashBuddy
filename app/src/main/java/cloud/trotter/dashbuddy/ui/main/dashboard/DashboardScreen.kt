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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented
import cloud.trotter.dashbuddy.core.designsystem.component.AppStatTile
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
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

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                        title = "Permissions Required",
                        subtitle = "DashBuddy needs your attention. Please complete the popup.",
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        textColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                // CASE 2: Permissions Granted, first run (The Guide)
                uiState.isFirstRun -> {
                    StatusCard(
                        title = "You have the Keys!",
                        subtitle = "Permissions granted. Let's personalize your strategy so DashBuddy knows what offers you like.",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavigateToWizard
                    ) { Text("Personalize Strategy") }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.completeSetup() }
                    ) { Text("Skip for now") }
                }

                // CASE 3: Ready — status card, a slim "tap for the bubble" pointer while
                // dashing, then the read-model period review (the primary economics), the
                // entry tiles, and a manual bubble trigger.
                else -> {
                    StatusCard(
                        title = uiState.statusText,
                        subtitle = if (uiState.isDashing) "You're on the clock." else "All systems go.",
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
                    ) { Text("Show Bubble") }
                }
            }
        }
    }
}

/** Placeholder shown for a rate figure that has no measurable denominator yet. */
private const val EMPTY_VALUE = "—"

/** The review windows offered by the period selector, in display order. */
private data class PeriodOption(val period: AnalyticsPeriod, val label: String)

private val PERIOD_OPTIONS = listOf(
    PeriodOption(AnalyticsPeriod.TODAY, "Today"),
    PeriodOption(AnalyticsPeriod.THIS_WEEK, "This week"),
    PeriodOption(AnalyticsPeriod.THIS_MONTH, "Month"),
    PeriodOption(AnalyticsPeriod.LIFETIME, "Lifetime"),
)

private fun periodLabel(period: AnalyticsPeriod): String =
    PERIOD_OPTIONS.first { it.period == period }.label

/**
 * Slim online pointer (#657): while a dash is active the review surface just points
 * back to the bubble (the live glance the bubble owns) — it does not mirror it. Tapping
 * re-shows the bubble via the existing Show-Bubble action.
 */
@Composable
private fun DashingStatusRow(onTap: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onTap)) {
        Text(
            text = "🟢 Dashing — tap for the bubble",
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.text,
        )
    }
}

/**
 * The primary home economics (#657): a Today / This week / Lifetime selector over the
 * read-model True Net · Net $/hr · Miles tiles. Frozen net (Σ each delivery's net against
 * its accepted cost basis + unattributed pay), so an economy edit never rewrites a past
 * period. Reactive but **not** live-ticking: the `economics` flow re-emits on each
 * projector commit (Room invalidation) and at midnight/week rollover, so the screen is
 * fresh-on-open without a `rememberNow()` clock — a historical period's $/hr is a fixed
 * value, so there is nothing to tick.
 */
@Composable
private fun PeriodReview(
    selectedPeriod: AnalyticsPeriod,
    economics: PeriodEconomics,
    onSelectPeriod: (AnalyticsPeriod) -> Unit,
) {
    val selectedLabel = periodLabel(selectedPeriod)
    AppSegmented(
        options = PERIOD_OPTIONS.map { it.label },
        selected = selectedLabel,
        onSelect = { label ->
            PERIOD_OPTIONS.firstOrNull { it.label == label }?.let { onSelectPeriod(it.period) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppStatTile(
            label = "True Net",
            value = Formats.money(economics.netProfit),
            sub = selectedLabel,
            valueColor = if (economics.netProfit >= 0.0) AppTheme.colors.good else AppTheme.colors.bad,
            modifier = Modifier.weight(1f),
        )
        AppStatTile(
            label = "Net/hr",
            value = economics.netPerHour?.let { Formats.money(it) } ?: EMPTY_VALUE,
            sub = selectedLabel,
            modifier = Modifier.weight(1f),
        )
        AppStatTile(
            label = "Miles",
            value = Formats.decimal(economics.totals.miles),
            sub = selectedLabel,
            modifier = Modifier.weight(1f),
        )
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
                label = "Analytics",
                modifier = Modifier.weight(1f),
                // #315 H1: routes to the Analytics hub (Money tab v1); other tabs stubbed.
                onClick = { onNavigate(Screen.Analytics.route) },
            )
            EntryTile(
                icon = Icons.Filled.Star,
                label = "Ratings",
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
                label = "Strategy",
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Screen.StrategySettings.route) },
            )
            EntryTile(
                icon = Icons.Filled.AttachMoney,
                label = "Economy",
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Screen.EconomySettings.route) },
            )
        }
    }
}

@Composable
private fun EntryTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier.clickable(onClick = onClick)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppTheme.colors.accent,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
fun StatusCard(
    title: String,
    subtitle: String,
    containerColor: Color,
    textColor: Color = contentColorFor(containerColor)
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = textColor
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(text = title, style = MaterialTheme.typography.headlineMedium)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
