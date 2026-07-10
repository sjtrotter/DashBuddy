package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod

/**
 * The Analytics hub (#315). Money / Decisions / Time / Patterns (H5) all render real content. A
 * **review** surface: UDF state in, `setTab`/`setPeriod` intents out
 * (Principle 1); reactive-fresh via the read-model Flows, no `rememberNow()` tick (a historical
 * period's figures are fixed).
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

            when (uiState.selectedTab) {
                AnalyticsTab.Money -> {
                    PeriodSelector(
                        selectedPeriod = uiState.selectedPeriod,
                        onSelectPeriod = viewModel::setPeriod,
                    )
                    Spacer(Modifier.height(16.dp))
                    MoneyTab(
                        economics = uiState.economics,
                        topStores = uiState.topStores,
                        recentSessions = uiState.recentSessions,
                        dailyEarnings = uiState.dailyEarnings,
                        onOpenSession = onOpenSession,
                    )
                }

                AnalyticsTab.Decisions -> {
                    PeriodSelector(
                        selectedPeriod = uiState.selectedPeriod,
                        onSelectPeriod = viewModel::setPeriod,
                    )
                    Spacer(Modifier.height(16.dp))
                    DecisionsTab(decisions = uiState.decisions)
                }

                AnalyticsTab.Time -> {
                    PeriodSelector(
                        selectedPeriod = uiState.selectedPeriod,
                        onSelectPeriod = viewModel::setPeriod,
                    )
                    Spacer(Modifier.height(16.dp))
                    TimeTab(time = uiState.time, period = uiState.selectedPeriod)
                }

                // Patterns (H5) is LIFETIME-scoped (rate/pattern-based) — deliberately NO period selector.
                AnalyticsTab.Patterns -> PatternsTab(
                    storeCards = uiState.storeReportCards,
                    heatmap = uiState.earningsHeatmap,
                )
            }
        }
    }
}

/** The hub tabs paired with their resolved label (#428 Half A) — identity stays the enum. */
private data class TabOption(val tab: AnalyticsTab, val label: String)

@Composable
private fun tabOptions(): List<TabOption> =
    AnalyticsTab.entries.map { TabOption(it, stringResource(it.labelRes)) }

/** The review windows offered by the Money period selector, in display order. */
private data class PeriodOption(val period: AnalyticsPeriod, val label: String)

@Composable
private fun periodOptions(): List<PeriodOption> = listOf(
    PeriodOption(AnalyticsPeriod.TODAY, stringResource(R.string.common_period_today)),
    PeriodOption(AnalyticsPeriod.THIS_WEEK, stringResource(R.string.common_period_week)),
    PeriodOption(AnalyticsPeriod.THIS_MONTH, stringResource(R.string.common_period_month)),
    PeriodOption(AnalyticsPeriod.LIFETIME, stringResource(R.string.common_period_lifetime)),
)

@Composable
private fun PeriodSelector(
    selectedPeriod: AnalyticsPeriod,
    onSelectPeriod: (AnalyticsPeriod) -> Unit,
) {
    val periodOptions = periodOptions()
    val selectedLabel = periodOptions.first { it.period == selectedPeriod }.label
    AppSegmented(
        options = periodOptions.map { it.label },
        selected = selectedLabel,
        onSelect = { label ->
            periodOptions.firstOrNull { it.label == label }?.let { onSelectPeriod(it.period) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
