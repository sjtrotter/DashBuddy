package cloud.trotter.log.gig.ui.main.playbook

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.component.CouldNotLoad
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.ui.components.HowNumbersWorkFooter
import cloud.trotter.log.gig.ui.components.MoreNumbersToggle
import cloud.trotter.log.gig.ui.components.RebuildingRecords
import cloud.trotter.log.gig.ui.components.UpdatingRecordsLine

/**
 * **Playbook** (#1024 section C) — the app's third destination: *Home = today · Analytics = the past ·
 * Playbook = the next move.*
 *
 * It exists because the surfaces it holds all answer **what should I do**, and all of them are
 * lifetime- or week-scoped by definition. Inside the period-first analytics hub they read as strangers:
 * the pager above them moves and nothing here changes, which looks like a bug and is actually the
 * design. Given their own destination, that scope is the point rather than an exception.
 *
 * Top to bottom:
 *  1. **This week's plan**, with live progress — the visible half of the Sunday grading loop;
 *  2. **When you earn** — the lifetime heatmap (Rate/Hours), with the plan's picked cells outlined, so
 *     the plan is visibly derived from the driver's own record;
 *  3. **Where you earn** — the store leaderboard (net / wait / recent);
 *  4. the shared More numbers preference and one **"How these numbers work"** disclosure.
 *
 * Simple mode shows the plan summary, Rate heatmap and top three stores by net; More reveals
 * per-window progress, the Hours mode and the full sortable store list.
 *
 * UDF: immutable state down, navigation lambdas up. No reads of its own — every source already ships
 * (see [PlaybookViewModel]). The only ticking value on the screen is the plan card's own hour boundary,
 * derived at that composable (Reactive-UI rule 2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybookScreen(
    onOpenSettings: () -> Unit,
    onOpenPlan: () -> Unit,
    viewModel: PlaybookViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PlaybookContent(
        uiState = uiState,
        onOpenSettings = onOpenSettings,
        onOpenPlan = onOpenPlan,
        onToggleShowMore = { viewModel.setShowMore(!uiState.showMore) },
        onRetry = viewModel::retry,
    )
}

/**
 * The Playbook's Scaffold (#1463 hoisted it out of [PlaybookScreen] so a screenshot test can render it
 * without Hilt). Stateless: state down, intents up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybookContent(
    uiState: PlaybookUiState,
    onOpenSettings: () -> Unit,
    onOpenPlan: () -> Unit,
    onToggleShowMore: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                // A tab root (#1447): no back button; Settings is the gear.
                title = { Text(stringResource(R.string.playbook_screen_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_home_title),
                        )
                    }
                },
            )
        },
    ) { padding ->
        // F1 (#1024 review): no block renders until the first read-model emission. Every block
        // below has an honest empty state, and on a cold open they would ALL be true for a frame —
        // "No plan saved for this week" over a plan the driver saved yesterday is not an honest
        // empty state, it is a wrong one shown briefly. Waiting is the honest option (§9);
        // placeholders shaped like the cards show meanwhile.
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (uiState.records == RecordsStatus.UPDATING) UpdatingRecordsLine()
            when {
                uiState.failed -> CouldNotLoad(
                    what = stringResource(R.string.playbook_could_not_load_what),
                    onRetry = onRetry,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                uiState.loading -> PlaybookPlaceholder()
                // The plan grade, the heatmap and the store figures are all held back.
                uiState.records == RecordsStatus.REBUILDING -> RebuildingRecords(Modifier.padding(horizontal = 16.dp))
                else -> PlaybookCards(uiState, onOpenPlan, onToggleShowMore)
            }
        }
    }
}

/** The loaded Playbook: the plan, when and where you earn, and the footer, in one scrolling column. */
@Composable
private fun ColumnScope.PlaybookCards(
    uiState: PlaybookUiState,
    onOpenPlan: () -> Unit,
    onToggleShowMore: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .fillMaxWidth(),
    ) {
        PlanProgressCard(
            showMore = uiState.showMore,
            grade = uiState.planGrade,
            onOpenPlan = onOpenPlan,
        )
        Spacer(Modifier.height(16.dp))

        // Declares the scope of the two cards below it — both read the whole record and are not a
        // view of any selected period (#979).
        AllTimeBadge()
        Spacer(Modifier.height(12.dp))

        WhenYouEarnCard(heatmap = uiState.heatmap, plan = uiState.savedPlan, showMore = uiState.showMore)
        Spacer(Modifier.height(16.dp))

        WhereYouEarnCard(cards = uiState.storeCards, showMore = uiState.showMore)
        Spacer(Modifier.height(16.dp))

        // #1024 part 3 follow-through: the plan-projection line ("lifetime aggregates of your
        // own record — not a guarantee") belongs on every screen that renders plan/best-stretch
        // rates. Home opts in; the Playbook renders the plan itself, so it opts in too.
        MoreNumbersToggle(showMore = uiState.showMore, onToggle = onToggleShowMore)
        Spacer(Modifier.height(16.dp))
        HowNumbersWorkFooter(
            includePlanProjection = true,
            extraNotes = listOf(stringResource(R.string.patterns_tab_stores_manual_note)),
        )
        Spacer(Modifier.height(24.dp))
    }
}
