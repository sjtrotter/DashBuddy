package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindows
import cloud.trotter.log.gig.domain.analytics.WindowGranularity
import cloud.trotter.log.gig.domain.format.formatMonthYear
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/** What the date picker offers besides Cancel (#1515): Recap offers both, the Shifts list Week, Month Month. */
enum class DatePickerChoice { WEEK, MONTH }

/**
 * The date picker's title-bar button (#1515): `date_range`, named "Choose a date" for screen readers and on long
 * press. It opens Material's date dialog on [contextDate] (a future date opens on today) with future dates disabled,
 * Cancel and [choices]. Week hands back the picked date ([onWeek]), Month its month ([onMonth]); the caller decides
 * what each opens. The shift page and the day page have no picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecapDatePickerButton(
    contextDate: LocalDate,
    choices: List<DatePickerChoice>,
    onWeek: (LocalDate) -> Unit = {},
    onMonth: (YearMonth) -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val name = stringResource(R.string.recap_picker_open)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(name) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.DateRange, contentDescription = name) }
    }
    if (open) {
        val today = deviceToday(LocalNowProvider.current)
        val state = rememberRecapDatePickerState(contextDate, today)
        val picked = state.selectedDate()
        DatePickerDialog(
            onDismissRequest = { open = false },
            dismissButton = { CancelButton(onCancel = { open = false }) },
            confirmButton = {
                ChoiceButtons(
                    picked = picked,
                    choices = choices,
                    onWeek = { open = false; onWeek(it) },
                    onMonth = { open = false; onMonth(it) },
                )
            },
        ) { RecapDatePickerBody(state, choices, today) }
    }
}

/**
 * The dialog's body and buttons on the dialog's surface, without a window: the screenshot and the Compose tests render
 * this. State down ([state] is hoisted), the choice up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecapDatePickerContent(
    state: DatePickerState,
    choices: List<DatePickerChoice>,
    today: LocalDate,
    onCancel: () -> Unit,
    onWeek: (LocalDate) -> Unit,
    onMonth: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = DatePickerDefaults.shape,
        color = DatePickerDefaults.colors().containerColor,
        tonalElevation = DatePickerDefaults.TonalElevation,
    ) {
        Column {
            RecapDatePickerBody(state, choices, today)
            Row(
                Modifier.align(Alignment.End).padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CancelButton(onCancel)
                ChoiceButtons(state.selectedDate(), choices, onWeek, onMonth)
            }
        }
    }
}

/**
 * The calendar, then one line per choice with the period it opens ("Week  Oct 26 to Nov 1", "Month  October 2026"),
 * following the selection. The calendar's own title and headline are left out: the period lines say what is picked,
 * and the dialog has no height to spare for both. The calendar gives way first, so the lines never clip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecapDatePickerBody(state: DatePickerState, choices: List<DatePickerChoice>, today: LocalDate) {
    Column {
        DatePicker(
            state = state,
            modifier = Modifier.weight(1f, fill = false),
            title = null,
            headline = null,
            showModeToggle = false,
        )
        PeriodLines(state.selectedDate(), choices, today)
    }
}

@Composable
private fun PeriodLines(picked: LocalDate?, choices: List<DatePickerChoice>, today: LocalDate) {
    if (picked == null) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag(PICKER_PERIODS_TAG)
            // Read as one: each label with its period.
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        choices.forEach { choice ->
            val (label, value) = when (choice) {
                DatePickerChoice.WEEK -> stringResource(R.string.recap_picker_week) to
                    windowRange(AnalyticsWindows.current(WindowGranularity.WEEK, picked), today).orEmpty()
                DatePickerChoice.MONTH -> stringResource(R.string.recap_picker_month) to formatMonthYear(YearMonth.from(picked))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.text2)
                Text(value, style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.text)
            }
        }
    }
}

@Composable
private fun CancelButton(onCancel: () -> Unit) {
    TextButton(onClick = onCancel) { Text(stringResource(R.string.recap_picker_cancel)) }
}

/** Week and Month, enabled once a date is picked (the dialog opens with one, so normally they are). */
@Composable
private fun ChoiceButtons(
    picked: LocalDate?,
    choices: List<DatePickerChoice>,
    onWeek: (LocalDate) -> Unit,
    onMonth: (YearMonth) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            when (choice) {
                DatePickerChoice.WEEK -> TextButton(onClick = { picked?.let(onWeek) }, enabled = picked != null) {
                    Text(stringResource(R.string.recap_picker_week))
                }
                DatePickerChoice.MONTH -> TextButton(onClick = { picked?.let { onMonth(YearMonth.from(it)) } }, enabled = picked != null) {
                    Text(stringResource(R.string.recap_picker_month))
                }
            }
        }
    }
}

/** The dialog's state: opened on [contextDate] (today when it is later), every date after [today] disabled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberRecapDatePickerState(contextDate: LocalDate, today: LocalDate): DatePickerState {
    val opening = utcMillis(if (contextDate.isAfter(today)) today else contextDate)
    return rememberDatePickerState(
        initialSelectedDateMillis = opening,
        initialDisplayedMonthMillis = opening,
        selectableDates = remember(today) { UpToToday(today) },
    )
}

/** Dates up to [today] are selectable; a later date or year is not. */
@OptIn(ExperimentalMaterial3Api::class)
internal class UpToToday(private val today: LocalDate) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = !utcDate(utcTimeMillis).isAfter(today)
    override fun isSelectableYear(year: Int): Boolean = year <= today.year
}

@OptIn(ExperimentalMaterial3Api::class)
private fun DatePickerState.selectedDate(): LocalDate? = selectedDateMillis?.let(::utcDate)

/** The device's local date from the composition's clock; never the wall clock directly. */
private fun deviceToday(clock: Clock): LocalDate = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate()

/** The date picker speaks UTC midnights. */
private fun utcMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/** The period lines' test tag. */
internal const val PICKER_PERIODS_TAG = "picker-periods"
