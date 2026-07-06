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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod

/**
 * The Analytics hub (#315). Money / Decisions / Time all render real content; Patterns (H5) still
 * shows a "coming soon" card. A **review** surface: UDF state in, `setTab`/`setPeriod` intents out
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
                title = { Text("Analytics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onExportCsv) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Export data (CSV)")
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
            AppSegmented(
                options = AnalyticsTab.entries.map { it.label },
                selected = uiState.selectedTab.label,
                onSelect = { label ->
                    AnalyticsTab.entries.firstOrNull { it.label == label }?.let(viewModel::setTab)
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

                AnalyticsTab.Patterns -> ComingSoonCard(uiState.selectedTab)
            }
        }
    }
}

/** The review windows offered by the Money period selector, in display order. */
private data class PeriodOption(val period: AnalyticsPeriod, val label: String)

private val PERIOD_OPTIONS = listOf(
    PeriodOption(AnalyticsPeriod.TODAY, "Today"),
    PeriodOption(AnalyticsPeriod.THIS_WEEK, "Week"),
    PeriodOption(AnalyticsPeriod.THIS_MONTH, "Month"),
    PeriodOption(AnalyticsPeriod.LIFETIME, "Lifetime"),
)

@Composable
private fun PeriodSelector(
    selectedPeriod: AnalyticsPeriod,
    onSelectPeriod: (AnalyticsPeriod) -> Unit,
) {
    val selectedLabel = PERIOD_OPTIONS.first { it.period == selectedPeriod }.label
    AppSegmented(
        options = PERIOD_OPTIONS.map { it.label },
        selected = selectedLabel,
        onSelect = { label ->
            PERIOD_OPTIONS.firstOrNull { it.label == label }?.let { onSelectPeriod(it.period) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ComingSoonCard(tab: AnalyticsTab) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${tab.label} — coming soon",
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.text,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "This tab lands in a later Analytics phase.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.text3,
        )
    }
}
