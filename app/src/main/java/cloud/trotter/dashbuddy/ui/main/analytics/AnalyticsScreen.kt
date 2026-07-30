package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented

/**
 * The Analytics hub (#315), **period-first** since #970. A `‹ ›` window pager + a recap hero own the
 * top of the screen; Money / Decisions / Time / Patterns (H5) render below as detail views of that
 * one window. A **review** surface: UDF state in, `setTab`/`stepWindow`/`setGranularity`/
 * `setCustomRange` intents out (Principle 1); reactive-fresh via the read-model Flows and a
 * once-a-day local-date anchor, with no `rememberNow()` tick (a historical window's figures are
 * fixed).
 *
 * The header's CSV action routes to the existing Data & Privacy export (#319) — the hub-header entry
 * point deferred from #671 (#315 H6); no new export logic, navigation only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    onBack: () -> Unit,
    onExportCsv: () -> Unit,
    onOpenSession: (String) -> Unit,
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // "(No session)" categorize flow (#660 piece 2) — opened from the Money-tab callout.
    var showAssignSheet by remember { mutableStateOf(false) }
    var showOrphanOfferSheet by remember { mutableStateOf(false) }
    // The #970 range picker (brief §3.2) — opened from the pager label or the `Custom…` chip.
    var showRangePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.analytics_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onExportCsv) {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = stringResource(R.string.analytics_screen_content_desc_export_csv),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .fillMaxSize(),
        ) {
            // Period-first (#970): the window control and the recap hero own the top of the screen;
            // the tabs below are detail views OF that window, not the primary navigation.
            PeriodPager(
                window = uiState.window,
                today = uiState.today,
                canStepBack = uiState.canStepBack,
                canStepForward = uiState.canStepForward,
                onStep = viewModel::stepWindow,
                onOpenPicker = { showRangePicker = true },
                onSelectGranularity = viewModel::setGranularity,
            )
            Spacer(Modifier.height(16.dp))
            RecapHero(
                window = uiState.window,
                today = uiState.today,
                economics = uiState.economics,
                previousEconomics = uiState.previousEconomics,
                decisions = uiState.decisions,
                dailyEarnings = uiState.dailyEarnings,
            )
            Spacer(Modifier.height(16.dp))

            val tabOptions = tabOptions()
            val selectedTabLabel = tabOptions.first { it.tab == uiState.selectedTab }.label
            AppSegmented(
                options = tabOptions.map { it.label },
                selected = selectedTabLabel,
                onSelect = { label ->
                    // Lookup happens against this already-resolved pairs list (#428 Half A), so
                    // selection stays keyed off the enum — not a raw resolved-string comparison
                    // that could collide across locales/tabs.
                    tabOptions.firstOrNull { it.label == label }?.let { viewModel.setTab(it.tab) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            // Each tab is a detail view of the window selected above — the per-tab period selector is
            // gone (#970); the pager at the top owns the window for all of them.
            when (uiState.selectedTab) {
                AnalyticsTab.Money -> MoneyTab(
                    economics = uiState.economics,
                    topStores = uiState.topStores,
                    recentSessions = uiState.recentSessions,
                    dailyEarnings = uiState.dailyEarnings,
                    orphanOfferGroups = uiState.orphanOfferGroups,
                    onOpenSession = onOpenSession,
                    onOpenNoSession = { showAssignSheet = true },
                    onOpenOrphanOffers = { showOrphanOfferSheet = true },
                )

                AnalyticsTab.Decisions -> DecisionsTab(decisions = uiState.decisions)

                AnalyticsTab.Time -> TimeTab(time = uiState.time, window = uiState.window)

                // Patterns (H5) is LIFETIME-scoped (rate/pattern-based) — deliberately NO period selector.
                AnalyticsTab.Patterns -> PatternsTab(
                    storeCards = uiState.storeReportCards,
                    heatmap = uiState.earningsHeatmap,
                )
            }
        }
    }

    if (showAssignSheet) {
        NoSessionAssignDialog(
            orphans = uiState.noSessionDeliveries,
            candidateSessionsFor = viewModel::candidateSessionsFor,
            onAssign = viewModel::assignToSession,
            onDismiss = { showAssignSheet = false },
        )
    }

    if (showOrphanOfferSheet) {
        OrphanOfferAttestDialog(
            groups = uiState.orphanOfferGroups,
            onResolve = viewModel::resolveOrphanOffer,
            onDismiss = { showOrphanOfferSheet = false },
        )
    }

    if (showRangePicker) {
        RangePickerSheet(
            window = uiState.window,
            today = uiState.today,
            viewModel = viewModel,
            onDismiss = { showRangePicker = false },
        )
    }
}

/** The hub tabs paired with their resolved label (#428 Half A) — identity stays the enum. */
private data class TabOption(val tab: AnalyticsTab, val label: String)

@Composable
private fun tabOptions(): List<TabOption> =
    AnalyticsTab.entries.map { TabOption(it, stringResource(it.labelRes)) }
