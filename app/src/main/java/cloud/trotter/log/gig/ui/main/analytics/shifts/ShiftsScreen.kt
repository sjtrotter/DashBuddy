package cloud.trotter.log.gig.ui.main.analytics.shifts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.component.CouldNotLoad
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindows
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.WindowGranularity
import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck
import cloud.trotter.log.gig.domain.analytics.recap.DatedShift
import cloud.trotter.log.gig.domain.analytics.recap.ShiftWeek
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.format.formatClockTime
import cloud.trotter.log.gig.domain.format.formatWeekdayMonthDay
import cloud.trotter.log.gig.ui.components.RebuildingRecords
import cloud.trotter.log.gig.ui.components.UpdatingRecordsLine
import cloud.trotter.log.gig.ui.main.analytics.HubPlaceholder
import cloud.trotter.log.gig.ui.main.analytics.allCheckLines
import cloud.trotter.log.gig.ui.main.analytics.landingRowOf
import cloud.trotter.log.gig.ui.main.analytics.recap.RecapNoShiftsYet
import cloud.trotter.log.gig.ui.main.analytics.windowRange
import java.time.LocalDate

/**
 * The Shifts list (#1515): every shift, newest first, under one header per Monday-to-Sunday week ("Oct 26 to Nov 1").
 * Opened from Recap's Shifts row, scrolled so Recap's week is at the top; that week shows "No shifts" when it has
 * none. A row is the date, the app with its times, Earned, and a shift's first check reason; a tap opens its shift
 * page. Title bar: Back and "Shifts". No ticker: a past shift's row is fixed, and a running one reads "to now".
 */
@Composable
fun ShiftsScreen(
    onBack: () -> Unit,
    onOpenSession: (sessionId: String, row: String?) -> Unit,
    viewModel: ShiftsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ShiftsContent(uiState, onBack, onOpenSession, viewModel::onVisibleWeeks, viewModel::retry)
}

/** The Shifts list's stateless body; tests and screenshots compose this. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShiftsContent(
    uiState: ShiftsUiState,
    onBack: () -> Unit,
    onOpenSession: (String, String?) -> Unit,
    onVisibleWeeks: (Set<LocalDate>) -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shifts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_content_desc_back))
                    }
                },
            )
        },
    ) { padding ->
        // The list waits for its content, so its opening position is computed against the loaded weeks (#1447).
        Column(Modifier.padding(padding).fillMaxSize()) {
            UpdatingRecordsLine(updating = uiState.records == RecordsStatus.UPDATING && !uiState.failed)
            when {
                uiState.failed -> CouldNotLoad(
                    what = stringResource(R.string.shifts_could_not_load_what),
                    onRetry = onRetry,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                !uiState.loaded -> HubPlaceholder()
                uiState.records == RecordsStatus.REBUILDING -> RebuildingRecords(Modifier.padding(16.dp))
                !uiState.anyShift -> Column(Modifier.padding(16.dp)) { RecapNoShiftsYet() }
                else -> ShiftsList(uiState, onOpenSession, onVisibleWeeks)
            }
        }
    }
}

/** One item of the list. [week] is the Monday it sits under; [key] is stable across reads. */
internal sealed interface ShiftsEntry {
    val week: LocalDate
    val key: String

    data class Header(override val week: LocalDate) : ShiftsEntry {
        override val key: String get() = "week:$week"
    }

    /** The one line of a week with no shift (only the target week can be one). */
    data class NoShifts(override val week: LocalDate) : ShiftsEntry {
        override val key: String get() = "none:$week"
    }

    /** [first] is the week's first row: it draws no divider above. */
    data class Shift(override val week: LocalDate, val item: DatedShift, val first: Boolean) : ShiftsEntry {
        override val key: String get() = "shift:${item.shift.sessionId}"
    }
}

/** [weeks] flattened in list order: each week's header, then its rows or its "No shifts" line. */
internal fun shiftsEntries(weeks: List<ShiftWeek>): List<ShiftsEntry> = weeks.flatMap { week ->
    listOf<ShiftsEntry>(ShiftsEntry.Header(week.start)) +
        if (week.shifts.isEmpty()) {
            listOf(ShiftsEntry.NoShifts(week.start))
        } else {
            week.shifts.mapIndexed { index, item -> ShiftsEntry.Shift(week.start, item, first = index == 0) }
        }
}

@Composable
private fun ShiftsList(
    uiState: ShiftsUiState,
    onOpenSession: (String, String?) -> Unit,
    onVisibleWeeks: (Set<LocalDate>) -> Unit,
) {
    val entries = remember(uiState.weeks) { shiftsEntries(uiState.weeks) }
    // Read once, when the list is first composed; a restored list keeps its own position.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = entries
            .indexOfFirst { it is ShiftsEntry.Header && it.week == uiState.targetWeek }
            .coerceAtLeast(0),
    )
    val currentEntries by rememberUpdatedState(entries)
    val report by rememberUpdatedState(onVisibleWeeks)
    LaunchedEffect(listState) {
        // snapshotFlow emits only when the set changes, so a scroll inside one week reports nothing.
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { currentEntries.getOrNull(it.index)?.week }.toSet()
        }.collect { report(it) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(SHIFTS_LIST_TAG),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(entries, key = { it.key }, contentType = { it::class }) { entry ->
            when (entry) {
                is ShiftsEntry.Header -> WeekHeader(entry.week, uiState.today)
                is ShiftsEntry.NoShifts -> Text(
                    stringResource(R.string.shifts_week_none),
                    Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTheme.colors.text2,
                )
                is ShiftsEntry.Shift -> ShiftRow(entry, uiState.checks[entry.item.shift.sessionId].orEmpty(), onOpenSession)
            }
        }
    }
}

/** "Oct 26 to Nov 1": the period bar's range, without "This week" or "Last week". */
@Composable
private fun WeekHeader(week: LocalDate, today: LocalDate) {
    Text(
        windowRange(AnalyticsWindows.current(WindowGranularity.WEEK, week), today).orEmpty(),
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp)
            .semantics { heading() }
            .testTag("shifts-week-$week"),
        style = MaterialTheme.typography.titleSmall,
        color = AppTheme.colors.text2,
    )
}

/**
 * "Wed, Oct 28" over "DoorDash, 2:30 PM to 6:10 PM" (a running shift: "to now"), Earned on the right (the em dash when
 * no pay was found), and the first check reason when the shift's week has been read. Opens the shift page at the row
 * concerned ([landingRowOf]).
 */
@Composable
private fun ShiftRow(entry: ShiftsEntry.Shift, checks: List<ShiftCheck>, onOpenSession: (String, String?) -> Unit) {
    val c = AppTheme.colors
    val shift = entry.item.shift
    val app = shift.platform.displayName
    val start = formatClockTime(shift.startedAt)
    val times = shift.endedAt?.let { stringResource(R.string.shift_page_app_times_format, app, start, formatClockTime(it)) }
        ?: stringResource(R.string.shifts_app_to_now_format, app, start)
    val reason = allCheckLines(checks, app).firstOrNull()
    Column(Modifier.fillMaxWidth()) {
        if (!entry.first) HorizontalDivider(color = c.line)
        Row(
            Modifier
                .fillMaxWidth()
                .testTag("shifts-row-${shift.sessionId}")
                .clickable(role = Role.Button) { onOpenSession(shift.sessionId, landingRowOf(checks)) }
                .padding(vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(formatWeekdayMonthDay(entry.item.date), style = MaterialTheme.typography.bodyLarge, color = c.text)
                Text(times, style = MaterialTheme.typography.bodyMedium, color = c.text2)
                reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.text2) }
            }
            Text(
                if (shift.paySeen) Formats.money(shift.earned) else EMPTY_VALUE,
                Modifier.padding(start = 12.dp),
                style = AppTheme.num.smNum,
                color = c.text,
            )
        }
    }
}

/** The list's test tag. */
internal const val SHIFTS_LIST_TAG = "shifts-list"
