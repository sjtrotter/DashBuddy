package cloud.trotter.log.gig.ui.main.analytics.month

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.component.AppCard
import cloud.trotter.log.gig.core.designsystem.component.CouldNotLoad
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindows
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.WindowGranularity
import cloud.trotter.log.gig.domain.analytics.recap.MonthComparison
import cloud.trotter.log.gig.domain.analytics.recap.MonthWeek
import cloud.trotter.log.gig.domain.analytics.recap.RecapMeasure
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.format.formatMonthYear
import cloud.trotter.log.gig.ui.components.HairlineDivider
import cloud.trotter.log.gig.ui.components.RebuildingRecords
import cloud.trotter.log.gig.ui.components.UpdatingRecordsLine
import cloud.trotter.log.gig.ui.main.analytics.DatePickerChoice
import cloud.trotter.log.gig.ui.main.analytics.HubPlaceholder
import cloud.trotter.log.gig.ui.main.analytics.MeasureSwitch
import cloud.trotter.log.gig.ui.main.analytics.PeriodBar
import cloud.trotter.log.gig.ui.main.analytics.RecapDatePickerButton
import cloud.trotter.log.gig.ui.main.analytics.datesRange
import cloud.trotter.log.gig.ui.main.analytics.windowRange
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.launch

/**
 * The Month screen (#1515): one calendar month on Recap's stack. Title bar: Back, "Month" and the date picker (Cancel,
 * Month), which moves this screen in place. Then the month bar "‹ October 2026 ›", the Earned / Est. Profit switch,
 * the month's total and its comparison, and one row per Monday-to-Sunday week cut at the month's edges. A row's tap
 * writes Recap's week and returns to Recap on it ([onOpenRecapWeek] after the write is stored). No ticker: the date
 * anchor re-emits at midnight, and a past month's figures are fixed.
 */
@Composable
fun MonthScreen(
    onBack: () -> Unit,
    onOpenRecapWeek: () -> Unit,
    viewModel: MonthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    MonthContent(
        uiState = uiState,
        onBack = onBack,
        onStep = viewModel::stepMonth,
        onPickMonth = viewModel::selectMonth,
        onSelectMeasure = viewModel::setMeasure,
        onOpenWeek = { monday ->
            scope.launch {
                if (viewModel.selectWeek(monday)) onOpenRecapWeek()
            }
        },
        onRetry = viewModel::retry,
    )
}

/** The Month screen's stateless body; tests and screenshots compose this. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonthContent(
    uiState: MonthUiState,
    onBack: () -> Unit,
    onStep: (Int) -> Unit,
    onPickMonth: (YearMonth) -> Unit,
    onSelectMeasure: (RecapMeasure) -> Unit,
    onOpenWeek: (monday: LocalDate) -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.month_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_content_desc_back))
                    }
                },
                actions = {
                    RecapDatePickerButton(uiState.month.atDay(1), listOf(DatePickerChoice.MONTH), onMonth = onPickMonth)
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            UpdatingRecordsLine(updating = uiState.records == RecordsStatus.UPDATING && !uiState.failed)
            when {
                uiState.failed -> CouldNotLoad(
                    what = stringResource(R.string.month_could_not_load_what),
                    onRetry = onRetry,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                !uiState.loaded -> HubPlaceholder()
                // Totals and comparisons are held back; the month bar needs no records.
                uiState.records == RecordsStatus.REBUILDING -> Column(Modifier.padding(16.dp)) {
                    MonthBar(uiState, onStep)
                    RebuildingRecords()
                }
                else -> MonthBody(uiState, onStep, onSelectMeasure, onOpenWeek)
            }
        }
    }
}

@Composable
private fun ColumnScope.MonthBody(
    uiState: MonthUiState,
    onStep: (Int) -> Unit,
    onSelectMeasure: (RecapMeasure) -> Unit,
    onOpenWeek: (LocalDate) -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MonthBar(uiState, onStep)
        MeasureSwitch(uiState.measure, onSelectMeasure)
        if (uiState.monthOff) {
            MonthOff(uiState)
        } else {
            MonthTotal(uiState)
            MonthWeekRows(uiState, onOpenWeek)
        }
    }
}

/** "‹ October 2026 ›": the screen's own month; `›` is disabled on the current month. */
@Composable
private fun MonthBar(uiState: MonthUiState, onStep: (Int) -> Unit) = PeriodBar(
    label = formatMonthYear(uiState.month),
    previousDescription = stringResource(R.string.month_previous),
    nextDescription = stringResource(R.string.month_next),
    canStepForward = uiState.canStepForward,
    onStep = onStep,
)

/** "October", or "October 2025" outside this year: the month's name as the period bar words a month. */
@Composable
private fun monthName(month: YearMonth, today: LocalDate): String =
    windowRange(AnalyticsWindows.current(WindowGranularity.MONTH, month.atDay(1)), today).orEmpty()

@Composable
private fun MonthTotal(uiState: MonthUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(uiState.total?.let { Formats.money(it) } ?: EMPTY_VALUE, style = AppTheme.num.heroNum, color = AppTheme.colors.text)
        uiState.comparison?.let { ComparisonLines(it, uiState.today, showThis = true) }
    }
}

/** A month with no shift: "No shifts in October", then the comparison's other side in words. */
@Composable
private fun MonthOff(uiState: MonthUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.month_none_format, monthName(uiState.month, uiState.today)),
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.text,
        )
        uiState.comparison?.let { ComparisonLines(it, uiState.today, showThis = false) }
    }
}

/**
 * "October $2,140.00" over "September $1,980.00" for a finished month; "Nov 1 to 8 $412.00" over "Oct 1 to 8 $455.50"
 * in the current month. The month before with no shift on those dates reads "no shifts". No percent.
 */
@Composable
private fun ComparisonLines(comparison: MonthComparison, today: LocalDate, showThis: Boolean) {
    val thisSpan: String
    val beforeSpan: String
    if (comparison.wholeMonths) {
        thisSpan = monthName(YearMonth.from(comparison.first), today)
        beforeSpan = monthName(YearMonth.from(comparison.beforeFirst), today)
    } else {
        thisSpan = datesRange(comparison.first, comparison.last, today)
        beforeSpan = datesRange(comparison.beforeFirst, comparison.beforeLast, today)
    }
    if (showThis) {
        Text(
            stringResource(R.string.recap_comparison_this_format, thisSpan, comparison.total?.let { Formats.money(it) } ?: EMPTY_VALUE),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.text,
        )
    }
    Text(
        if (comparison.beforeNoShift) {
            stringResource(R.string.month_comparison_none_format, beforeSpan)
        } else {
            stringResource(R.string.recap_comparison_this_format, beforeSpan, comparison.beforeTotal?.let { Formats.money(it) } ?: EMPTY_VALUE)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.text2,
    )
}

/**
 * One row per week, oldest first: its dates ("Oct 1 to 4", one-day "Nov 30"; the current week "This week, Nov 9 to
 * 15") and its amount, "No shifts" for a week off, the em dash when its pay was never found.
 */
@Composable
private fun MonthWeekRows(uiState: MonthUiState, onOpenWeek: (LocalDate) -> Unit) {
    val weeks = uiState.weeks
    if (weeks.isEmpty()) return
    AppCard(Modifier.fillMaxWidth()) {
        weeks.forEachIndexed { index, week ->
            if (index > 0) HairlineDivider()
            WeekRow(week, uiState, onOpenWeek)
        }
    }
}

@Composable
private fun WeekRow(week: MonthWeek, uiState: MonthUiState, onOpenWeek: (LocalDate) -> Unit) {
    val c = AppTheme.colors
    val dates = datesRange(week.first, week.last, uiState.today)
    val label = if (week.holds(uiState.today)) {
        stringResource(R.string.recap_period_format, stringResource(R.string.analytics_window_this_week), dates)
    } else {
        dates
    }
    val amount = when {
        week.noShift -> stringResource(R.string.shifts_week_none)
        else -> week.value(uiState.measure)?.let { Formats.money(it) } ?: EMPTY_VALUE
    }
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("month-week-${week.monday}")
            .clickable(onClickLabel = stringResource(R.string.month_week_open), role = Role.Button) { onOpenWeek(week.monday) }
            .padding(vertical = 12.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.text)
        Text(
            amount,
            Modifier.padding(start = 12.dp),
            style = if (week.noShift) MaterialTheme.typography.bodyLarge else AppTheme.num.smNum,
            color = if (week.noShift) c.text2 else c.text,
        )
    }
}
