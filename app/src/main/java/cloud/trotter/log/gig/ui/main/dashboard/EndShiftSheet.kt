package cloud.trotter.log.gig.ui.main.dashboard

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.core.designsystem.time.rememberNow
import cloud.trotter.log.gig.domain.format.formatClockTime
import cloud.trotter.log.gig.domain.format.formatMonthDay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * #1533: end a live shift by hand. Stateless: [form] is hoisted to the host, which dispatches the end
 * through [onConfirm]. The end button stays disabled until the dasher confirms with the switch and the
 * chosen time is inside the shift's start and now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EndShiftSheet(
    candidates: List<EndShiftCandidate>,
    form: EndShiftForm,
    onFormChange: (EndShiftForm) -> Unit,
    onConfirm: (EndShiftCandidate, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        EndShiftContent(candidates, form, onFormChange, onConfirm, onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EndShiftContent(
    candidates: List<EndShiftCandidate>,
    form: EndShiftForm,
    onFormChange: (EndShiftForm) -> Unit,
    onConfirm: (EndShiftCandidate, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AppTheme.colors
    val now by rememberNow()
    val zone = remember { ZoneId.systemDefault() }
    val selected = candidates.firstOrNull { it.sessionId == form.sessionId }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(stringResource(R.string.end_shift_title), style = MaterialTheme.typography.titleLarge, color = c.text)
        if (candidates.size > 1 || selected == null) {
            Spacer(Modifier.height(8.dp))
            candidates.forEach { candidate ->
                CandidateRow(candidate, candidate == selected, zone) {
                    onFormChange(EndShiftForm(sessionId = candidate.sessionId))
                }
            }
        }
        if (selected != null) {
            ShiftForm(selected, form, now, zone, onFormChange, onConfirm, onDismiss)
        } else {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.end_shift_cancel))
            }
        }
    }

    if (selected != null) {
        val endAt = form.endAtFor(selected)
        when (form.picker) {
            EndShiftPicker.DATE -> EndDatePicker(selected, endAt, now, zone,
                onPick = { onFormChange(form.copy(endAt = it, picker = null)) },
                onDismiss = { onFormChange(form.copy(picker = null)) })
            EndShiftPicker.TIME -> EndTimePicker(endAt, zone,
                onPick = { onFormChange(form.copy(endAt = it, picker = null)) },
                onDismiss = { onFormChange(form.copy(picker = null)) })
            null -> Unit
        }
    }
}

@Composable
private fun CandidateRow(candidate: EndShiftCandidate, isSelected: Boolean, zone: ZoneId, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.end_shift_pick_row, candidate.platform.appName, dateTime(candidate.startedAt, zone)),
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun ShiftForm(
    candidate: EndShiftCandidate,
    form: EndShiftForm,
    now: Long,
    zone: ZoneId,
    onFormChange: (EndShiftForm) -> Unit,
    onConfirm: (EndShiftCandidate, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AppTheme.colors
    val app = candidate.platform.appName
    val endAt = form.endAtFor(candidate)
    val error = candidate.errorFor(endAt, now)

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.end_shift_intro, app), style = MaterialTheme.typography.bodyMedium, color = c.text2)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.end_shift_started, dateTime(candidate.startedAt, zone)),
        style = MaterialTheme.typography.bodyMedium,
        color = c.text,
    )

    Spacer(Modifier.height(16.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = form.confirmed,
                role = Role.Switch,
                onValueChange = { onFormChange(form.copy(confirmed = it)) },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.end_shift_confirm_switch, app),
            style = MaterialTheme.typography.bodyLarge,
            color = c.text,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = form.confirmed, onCheckedChange = null)
    }

    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.end_shift_ended_at), style = MaterialTheme.typography.labelLarge, color = c.text)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onFormChange(form.copy(picker = EndShiftPicker.DATE)) }) {
            Text(formatMonthDay(localDate(endAt, zone)))
        }
        OutlinedButton(onClick = { onFormChange(form.copy(picker = EndShiftPicker.TIME)) }) {
            Text(formatClockTime(endAt, zone))
        }
    }
    if (form.endAt == null) {
        Text(stringResource(R.string.end_shift_suggested), style = MaterialTheme.typography.bodySmall, color = c.neutral)
    }

    when (error) {
        EndShiftError.BEFORE_START -> Notice(
            stringResource(R.string.end_shift_error_before_start, dateTime(candidate.startedAt, zone)), c.bad,
        )
        EndShiftError.AFTER_NOW -> Notice(stringResource(R.string.end_shift_error_after_now, dateTime(now, zone)), c.bad)
        null -> if (candidate.hasActivityAfter(endAt)) Notice(stringResource(R.string.end_shift_warning_activity), c.warn)
    }
    if (candidate.hasOpenDelivery) Notice(stringResource(R.string.end_shift_open_delivery), c.text2)

    Spacer(Modifier.height(24.dp))
    val endLabel = if (localDate(endAt, zone) == localDate(now, zone)) formatClockTime(endAt, zone) else dateTime(endAt, zone)
    Button(
        onClick = { onConfirm(candidate, endAt) },
        enabled = form.confirmed && error == null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.end_shift_confirm, endLabel)) }
    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.end_shift_cancel))
    }
}

@Composable
private fun Notice(text: String, color: androidx.compose.ui.graphics.Color) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

/** The date dialog keeps the chosen time of day; days outside the shift's start and today are not offered. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndDatePicker(
    candidate: EndShiftCandidate,
    endAt: Long,
    now: Long,
    zone: ZoneId,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = localDate(candidate.startedAt, zone)
    val last = localDate(now, zone)
    val state = rememberDatePickerState(
        initialSelectedDateMillis = utcMidnight(localDate(endAt, zone)),
        selectableDates = remember(first, last) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val day = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !day.isBefore(first) && !day.isAfter(last)
                }
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                if (picked == null) onDismiss() else onPick(at(picked, localTime(endAt, zone), zone))
            }) { Text(stringResource(R.string.end_shift_picker_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.end_shift_cancel)) } },
    ) { DatePicker(state = state) }
}

/** The time dialog keeps the chosen date; seconds are dropped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndTimePicker(endAt: Long, zone: ZoneId, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val time = localTime(endAt, zone)
    val state = rememberTimePickerState(
        initialHour = time.hour,
        initialMinute = time.minute,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    TimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(at(localDate(endAt, zone), LocalTime.of(state.hour, state.minute), zone))
            }) { Text(stringResource(R.string.end_shift_picker_ok)) }
        },
        title = { Text(stringResource(R.string.end_shift_ended_at)) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.end_shift_cancel)) } },
    ) { TimePicker(state = state) }
}

private fun localDate(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

private fun localTime(millis: Long, zone: ZoneId): LocalTime = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()

private fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Long = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()

private fun utcMidnight(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** "Nov 5, 5:49 PM". */
private fun dateTime(millis: Long, zone: ZoneId): String =
    "${formatMonthDay(localDate(millis, zone))}, ${formatClockTime(millis, zone)}"
