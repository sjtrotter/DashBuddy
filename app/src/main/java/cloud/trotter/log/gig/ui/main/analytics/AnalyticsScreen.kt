package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.component.AppSegmented
import cloud.trotter.log.gig.core.designsystem.component.CouldNotLoad
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.recap.RecapMeasure
import cloud.trotter.log.gig.ui.components.RebuildingRecords
import cloud.trotter.log.gig.ui.components.UpdatingRecordsLine
import cloud.trotter.log.gig.ui.main.analytics.recap.DayTap
import cloud.trotter.log.gig.ui.main.analytics.recap.RecapDayChart
import cloud.trotter.log.gig.ui.main.analytics.recap.RecapNoShiftsYet
import cloud.trotter.log.gig.ui.main.analytics.recap.RecapRows
import cloud.trotter.log.gig.ui.main.analytics.recap.RecapShiftsToCheck
import cloud.trotter.log.gig.ui.main.analytics.recap.RecapTiles
import cloud.trotter.log.gig.ui.main.analytics.recap.RecapTotal
import cloud.trotter.log.gig.ui.main.analytics.recap.RecapWeekOff
import java.time.LocalDate
import java.time.YearMonth

/**
 * Recap, the week (#1515): the period bar, the Earned / Est. Profit switch, the week's total and its comparison, the
 * chart by day, two tiles, the shifts to check and the rows. UDF: state in, `stepWeek` / `setMeasure` intents out;
 * fresh through the read-model Flows and a once-a-day date anchor, with no `rememberNow()` tick (a past week's
 * figures are fixed). A day with one shift opens it, a day with more opens the day page; the Shifts row opens the
 * Shifts list at the week shown.
 */
@Composable
fun AnalyticsScreen(
    onOpenSettings: () -> Unit,
    onOpenSession: (sessionId: String, row: String?) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onOpenShifts: (weekStart: LocalDate) -> Unit,
    onOpenMonth: (YearMonth, RecapMeasure) -> Unit,
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAssignSheet by remember { mutableStateOf(false) }

    AnalyticsContent(
        uiState = uiState,
        onOpenSettings = onOpenSettings,
        onStep = viewModel::stepWeek,
        onSelectMeasure = viewModel::setMeasure,
        onOpenDay = { tap ->
            when (tap) {
                is DayTap.Shift -> onOpenSession(tap.sessionId, null)
                is DayTap.Day -> onOpenDay(tap.date)
            }
        },
        onOpenShift = onOpenSession,
        onOpenShifts = onOpenShifts,
        onOpenNoSession = { showAssignSheet = true },
        onRetry = viewModel::retry,
        onPickWeek = viewModel::selectWeek,
        onPickMonth = { month -> onOpenMonth(month, uiState.measure) },
    )

    // The assign dialog is a correction action: held back while the records rebuild or a read has failed.
    if (showAssignSheet && uiState.figuresShown()) {
        NoSessionAssignDialog(
            orphans = uiState.noSessionDeliveries,
            candidateSessionsFor = viewModel::candidateSessionsFor,
            onAssign = viewModel::assignToSession,
            onDismiss = { showAssignSheet = false },
        )
    }
}

/**
 * Recap's Scaffold, stateless (a screenshot or Compose test renders it without Hilt). A tab root: no back button. The
 * title bar holds the date picker (Cancel, Week, Month; it opens on the week's Monday, or today in this week) and
 * Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnalyticsContent(
    uiState: AnalyticsUiState,
    onOpenSettings: () -> Unit,
    onStep: (Int) -> Unit,
    onSelectMeasure: (RecapMeasure) -> Unit,
    onOpenDay: (DayTap) -> Unit,
    onOpenShift: (String, String?) -> Unit,
    onOpenShifts: (weekStart: LocalDate) -> Unit,
    onOpenNoSession: () -> Unit,
    onRetry: () -> Unit,
    onPickWeek: (LocalDate) -> Unit = {},
    onPickMonth: (YearMonth) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.analytics_screen_title)) },
                actions = {
                    RecapDatePickerButton(
                        contextDate = recapPickerDate(uiState),
                        choices = listOf(DatePickerChoice.WEEK, DatePickerChoice.MONTH),
                        onWeek = onPickWeek,
                        onMonth = onPickMonth,
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_home_title))
                    }
                },
            )
        },
    ) { padding ->
        // #1447: the scrolling column waits for its content. Composed over the placeholder, a restored scroll
        // offset would be clamped to the short content and lost.
        Column(Modifier.padding(padding).fillMaxSize()) {
            UpdatingRecordsLine(updating = uiState.records == RecordsStatus.UPDATING && !uiState.failed)
            when {
                uiState.failed -> CouldNotLoad(
                    what = stringResource(R.string.analytics_could_not_load_what),
                    onRetry = onRetry,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                !uiState.loaded -> HubPlaceholder()
                // Totals, comparisons and correction actions are held back; the period bar needs no records.
                uiState.records == RecordsStatus.REBUILDING -> Column(Modifier.padding(16.dp)) {
                    RecapPeriodBar(uiState.week, uiState.today, uiState.canStepForward, onStep)
                    RebuildingRecords()
                }
                !uiState.anyShift -> Column(Modifier.padding(16.dp)) { RecapNoShiftsYet() }
                else -> RecapWeekBody(uiState, onStep, onSelectMeasure, onOpenDay, onOpenShift, onOpenShifts, onOpenNoSession)
            }
        }
    }
}

/** The loaded week, top to bottom, in one scrolling column. */
@Composable
private fun ColumnScope.RecapWeekBody(
    uiState: AnalyticsUiState,
    onStep: (Int) -> Unit,
    onSelectMeasure: (RecapMeasure) -> Unit,
    onOpenDay: (DayTap) -> Unit,
    onOpenShift: (String, String?) -> Unit,
    onOpenShifts: (weekStart: LocalDate) -> Unit,
    onOpenNoSession: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RecapPeriodBar(uiState.week, uiState.today, uiState.canStepForward, onStep)
        MeasureSwitch(uiState.measure, onSelectMeasure)
        if (uiState.weekOff) {
            RecapWeekOff(uiState)
        } else {
            RecapTotal(uiState)
            RecapDayChart(uiState.days, uiState.lastWeekDays, uiState.measure, onOpenDay)
            RecapTiles(uiState)
            RecapShiftsToCheck(uiState.shiftsToCheck, onOpenShift)
        }
        // Deliveries with no shift are the one correction Recap still offers: the assign dialog is their only entry.
        NeedsALookCard(reviewItems(uiState.economics, onOpenNoSession).filter { it.action != null })
        RecapRows(uiState, onOpenShifts = { uiState.week.startDate?.let(onOpenShifts) }, showShifts = !uiState.weekOff)
    }
}

/** The date Recap's picker opens on: the week's Monday, or today when the week is this week. */
private fun recapPickerDate(uiState: AnalyticsUiState): LocalDate =
    uiState.week.startDate?.takeUnless { uiState.week.contains(uiState.today) } ?: uiState.today

/** The switch, Earned first; identity stays the enum, never the resolved label. Recap's and the Month screen's. */
@Composable
internal fun MeasureSwitch(selected: RecapMeasure, onSelect: (RecapMeasure) -> Unit) {
    val options = listOf(
        RecapMeasure.EARNED to stringResource(R.string.recap_measure_earned),
        RecapMeasure.PROFIT to stringResource(R.string.recap_measure_profit),
    )
    AppSegmented(
        options = options.map { it.second },
        selected = options.first { it.first == selected }.second,
        onSelect = { label -> options.firstOrNull { it.second == label }?.let { onSelect(it.first) } },
        modifier = Modifier.fillMaxWidth(),
    )
}
