package cloud.trotter.log.gig.ui.main.analytics.day

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
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
import cloud.trotter.log.gig.domain.analytics.ANALYTICS_MONEY_EPSILON
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.recap.DayShift
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.format.formatClockTime
import cloud.trotter.log.gig.domain.format.formatWeekdayMonthDay
import cloud.trotter.log.gig.ui.components.HairlineDivider
import cloud.trotter.log.gig.ui.components.RebuildingRecords
import cloud.trotter.log.gig.ui.components.UpdatingRecordsLine
import cloud.trotter.log.gig.ui.main.analytics.HubPlaceholder
import cloud.trotter.log.gig.ui.main.analytics.isLargeText

/**
 * The day page (#1515): a day with two or more shifts, opened from Recap. Its title is the date ("Wed, Oct 28") with
 * Back; the body is the day's Earned, online time, miles and Est. Profit/hr, then its shifts newest first, each
 * opening its shift page. No period bar and no switch.
 */
@Composable
fun DayScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    viewModel: DayViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DayContent(uiState, onBack, onOpenSession, viewModel::retry)
}

/** The day page's stateless body; tests and screenshots compose this. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayContent(uiState: DayUiState, onBack: () -> Unit, onOpenSession: (String) -> Unit, onRetry: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.date?.let { formatWeekdayMonthDay(it) }.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_content_desc_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            UpdatingRecordsLine(updating = uiState.records == RecordsStatus.UPDATING && !uiState.failed)
            when {
                uiState.failed -> CouldNotLoad(
                    what = stringResource(R.string.recap_day_could_not_load_what),
                    onRetry = onRetry,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                !uiState.loaded -> HubPlaceholder()
                uiState.records == RecordsStatus.REBUILDING -> RebuildingRecords(Modifier.padding(16.dp))
                else -> DayBody(uiState, onOpenSession)
            }
        }
    }
}

@Composable
private fun DayBody(uiState: DayUiState, onOpenSession: (String) -> Unit) {
    val c = AppTheme.colors
    val e = uiState.economics
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(stringResource(R.string.recap_measure_earned), style = MaterialTheme.typography.labelMedium, color = c.text2)
            Text(Formats.money(e.grossEarnings), style = AppTheme.num.heroNum, color = c.text)
            if (e.cash > ANALYTICS_MONEY_EPSILON) {
                Text(
                    stringResource(R.string.recap_cash_included_format, Formats.money(e.cash)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.text2,
                )
            }
        }
        DayFigures(uiState)
        AppCard(Modifier.fillMaxWidth()) {
            uiState.shifts.forEachIndexed { index, shift ->
                if (index > 0) HairlineDivider()
                ShiftRow(shift, onOpenSession)
            }
        }
    }
}

/** Hours online, miles driven and Est. Profit/hr; stacked at large text. */
@Composable
private fun DayFigures(uiState: DayUiState) {
    val e = uiState.economics
    val figures = listOf(
        Formats.decimal(e.totals.onlineDuration / 3_600_000.0) to stringResource(R.string.recap_day_online),
        (if (e.totals.miles > 0.0) Formats.decimal(e.totals.miles, 0) else EMPTY_VALUE) to stringResource(R.string.recap_day_miles),
        (e.netPerHour?.let { Formats.money(it) } ?: EMPTY_VALUE) to stringResource(R.string.shift_page_profit_per_hour),
    )
    val tile: @Composable (Modifier, Pair<String, String>) -> Unit = { modifier, (value, label) ->
        AppCard(modifier) {
            Text(value, style = AppTheme.num.lgNum, color = AppTheme.colors.text)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.text2)
        }
    }
    if (isLargeText()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { figures.forEach { tile(Modifier.fillMaxWidth(), it) } }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { figures.forEach { tile(Modifier.weight(1f), it) } }
    }
}

/** "DoorDash", "4:58 PM to 11:04 PM" (or "since 4:58 PM") and its Earned; opens its shift page. */
@Composable
private fun ShiftRow(shift: DayShift, onOpenSession: (String) -> Unit) {
    val c = AppTheme.colors
    val app = shift.platform.displayName
    val start = formatClockTime(shift.startedAt)
    val times = shift.endedAt?.let { stringResource(R.string.shift_page_app_times_format, app, start, formatClockTime(it)) }
        ?: stringResource(R.string.shift_page_app_since_format, app, start)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onOpenSession(shift.sessionId) }
            .padding(vertical = 12.dp),
    ) {
        Text(times, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.text)
        Text(
            if (shift.paySeen) Formats.money(shift.earned) else stringResource(R.string.recap_day_no_pay),
            style = AppTheme.num.smNum,
            color = c.text,
        )
    }
}
