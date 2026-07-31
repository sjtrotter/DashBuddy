package cloud.trotter.dashbuddy.ui.main.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme

/**
 * **Weekly Plan** (#981 / brief §8) — the redesign's new surface and its retention hook: a set of
 * hour windows drawn from the driver's own record, with the value of following the plan stated next to
 * the value of not bothering.
 *
 * Order, top to bottom, exactly as specced:
 *  1. last week's grade (when there is one — the loop that earns the notification its place);
 *  2. the target selector (`8h · 12h · 20h · $ goal`);
 *  3. the headline value **and** the plan-vs-random comparison — never one without the other;
 *  4. the suggested windows, each with its evidence line, a `BEST` pill on the top one, and the
 *     weekdays that were NOT picked with the measured reason;
 *  5. "where the plan came from" — the same lifetime heatmap the Patterns tab renders, with the
 *     picked cells outlined;
 *  6. the locked growth rows (area demand, year-over-year), carrying no data because there is none;
 *  7. Save this plan.
 *
 * **Honesty rules (§9), rendered not assumed.** Every projection says whose data it is and that it is
 * not a promise; a window quotes the number of the driver's own days behind it; thin history is stated
 * instead of being smoothed; and with no record at all the screen says so rather than showing an empty
 * plan that looks like a bad week.
 *
 * UDF: immutable state down, intents up (`setTarget`/`moveWindow`/`dropWindow`/`savePlan`). No
 * `rememberNow()` ticker — a week's plan holds nothing that goes stale while it is looked at; the
 * ViewModel's record flow and midnight anchor keep it fresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyPlanScreen(
    onBack: () -> Unit,
    viewModel: WeeklyPlanViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.weekly_plan_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
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
            WeekHeader(weekStart = uiState.weekStart)
            Spacer(Modifier.height(16.dp))

            uiState.lastWeekGrade?.let { grade ->
                LastWeekGradeCard(grade)
                Spacer(Modifier.height(16.dp))
            }

            if (!uiState.loading && !uiState.hasRecord) {
                // No record at all: an empty plan would read as "your week is worth nothing".
                NoRecordCard()
                Spacer(Modifier.height(16.dp))
            }

            TargetSelectorCard(target = uiState.target, onSelect = viewModel::setTarget)
            Spacer(Modifier.height(16.dp))

            if (uiState.hasRecord) {
                PlanValueCard(plan = uiState.plan)
                Spacer(Modifier.height(16.dp))

                SuggestedWindowsCard(
                    plan = uiState.plan,
                    onMove = viewModel::moveWindow,
                    onDrop = viewModel::dropWindow,
                    onUndo = viewModel::undoLastEdit,
                )
                Spacer(Modifier.height(16.dp))

                PlanProvenanceCard(heatmap = uiState.heatmap, plan = uiState.plan)
                Spacer(Modifier.height(16.dp))

                SavePlanCard(
                    isSaved = uiState.isSaved,
                    canSave = uiState.plan.windows.isNotEmpty(),
                    onSave = { viewModel.savePlan() },
                )
                Spacer(Modifier.height(16.dp))
            }

            GrowthRows()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** `Week of Mon, Aug 3` — which week the driver is planning, stated before anything claims anything. */
@Composable
private fun WeekHeader(weekStart: java.time.LocalDate) {
    val c = AppTheme.colors
    Text(
        text = stringResource(R.string.weekly_plan_week_of_format, weekLabel(weekStart)),
        style = MaterialTheme.typography.titleMedium,
        color = c.text,
    )
}
