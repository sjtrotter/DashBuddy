package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindow
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindows
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.WindowGranularity
import cloud.trotter.dashbuddy.domain.format.formatMonthYear
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * The range-picker bottom sheet (#970 / brief §3.2) — presets on top, a month-paged calendar below,
 * and a commit button that **states the range it will show** (`Show Jul 13 – 19`).
 *
 * Calendar rules: weeks start Monday (the same pay-week anchor every aggregate uses), **future days
 * are disabled** (there is nothing there to review), today carries an outline, a day the driver
 * earned on carries a dot whose opacity tracks how much (brief §3.2), and tapping picks the range's
 * start then its end (a second tap before the start re-anchors the start instead of drawing a
 * backwards range).
 *
 * The earning dots reuse the same arbitrary-window per-day read the Money chart uses — no bespoke
 * query — so a dot and a bar can never disagree (Principle 5).
 *
 * Data-in / lambdas-out: the visible month and its per-day earnings are hoisted (the ViewModel owns
 * them, since the dots are a repository read), and only the in-progress range selection is
 * sheet-local — it must not touch the hub's window until the driver commits.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RangePickerSheet(
    window: AnalyticsWindow,
    today: LocalDate,
    month: YearMonth,
    monthDays: List<DailyEarnings>,
    onMonthChange: (YearMonth) -> Unit,
    onPickPreset: (WindowGranularity, Int) -> Unit,
    onCommitRange: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    // Selection is sheet-local until committed — opening the sheet never changes the hub's window.
    var pendingStart by remember { mutableStateOf(window.startDate) }
    var pendingEnd by remember { mutableStateOf(window.endDateInclusive) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.analytics_picker_title),
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.colors.text,
            )
            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.analytics_picker_presets),
                style = MaterialTheme.typography.labelMedium,
                color = AppTheme.colors.text3,
            )
            Spacer(Modifier.height(8.dp))
            PresetRow(
                onPick = { granularity, offset ->
                    onPickPreset(granularity, offset)
                    onDismiss()
                },
            )

            Spacer(Modifier.height(20.dp))
            MonthHeader(
                month = month,
                onPrevious = { onMonthChange(month.minusMonths(1)) },
                onNext = { onMonthChange(month.plusMonths(1)) },
                canGoNext = month < YearMonth.from(today),
            )
            Spacer(Modifier.height(8.dp))
            WeekdayHeader()
            MonthGrid(
                month = month,
                today = today,
                days = monthDays,
                selectionStart = pendingStart,
                selectionEnd = pendingEnd,
                onPick = { picked ->
                    val start = pendingStart
                    val end = pendingEnd
                    when {
                        // A completed range, or no range yet, starts a new one.
                        start == null || end != null -> {
                            pendingStart = picked
                            pendingEnd = null
                        }
                        // A second tap before the anchor re-anchors rather than drawing backwards.
                        picked.isBefore(start) -> pendingStart = picked
                        else -> pendingEnd = picked
                    }
                },
            )

            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.analytics_picker_dot_legend),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.text3,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (pendingStart == null || pendingEnd != null) {
                    stringResource(R.string.analytics_picker_pick_start)
                } else {
                    stringResource(R.string.analytics_picker_pick_end)
                },
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.text3,
            )

            Spacer(Modifier.height(16.dp))
            val start = pendingStart
            val end = pendingEnd ?: pendingStart
            Button(
                onClick = {
                    if (start != null && end != null) {
                        onCommitRange(start, end)
                        onDismiss()
                    }
                },
                enabled = start != null && end != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val label = if (start != null && end != null) {
                    WindowLabel.range(AnalyticsWindows.custom(start, end), today).orEmpty()
                } else {
                    ""
                }
                Text(stringResource(R.string.analytics_picker_show_format, label))
            }
        }
    }
}

/** The §3.2 preset row — each chip is a relative selection, which is exactly what the pager persists. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetRow(onPick: (WindowGranularity, Int) -> Unit) {
    val presets = listOf(
        Triple(stringResource(R.string.analytics_window_today), WindowGranularity.DAY, 0),
        Triple(stringResource(R.string.analytics_window_yesterday), WindowGranularity.DAY, -1),
        Triple(stringResource(R.string.analytics_window_this_week), WindowGranularity.WEEK, 0),
        Triple(stringResource(R.string.analytics_window_last_week), WindowGranularity.WEEK, -1),
        Triple(stringResource(R.string.analytics_window_this_month), WindowGranularity.MONTH, 0),
        Triple(stringResource(R.string.analytics_window_last_month), WindowGranularity.MONTH, -1),
        Triple(stringResource(R.string.analytics_window_lifetime), WindowGranularity.LIFETIME, 0),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { (label, granularity, offset) ->
            AppChip(
                text = label,
                uppercase = false,
                color = AppTheme.colors.accent,
                container = AppTheme.colors.accentDim,
                modifier = Modifier.clickable(role = Role.Button) { onPick(granularity, offset) },
            )
        }
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    canGoNext: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.analytics_picker_previous_month),
            )
        }
        Text(
            text = formatMonthYear(month),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = AppTheme.colors.text,
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.analytics_picker_next_month),
            )
        }
    }
}

/** Monday-first weekday initials — the same week anchor every aggregate uses. */
@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth()) {
        MONDAY_FIRST_WEEK.forEach { day ->
            Text(
                text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.colors.text3,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val MONDAY_FIRST_WEEK = listOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
)

@Composable
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    days: List<DailyEarnings>,
    selectionStart: LocalDate?,
    selectionEnd: LocalDate?,
    onPick: (LocalDate) -> Unit,
) {
    val grossByDay = remember(days) { days.associate { it.date to it.gross } }
    val maxGross = remember(days) { days.maxOfOrNull { it.gross }?.takeIf { it > 0.0 } }
    // Leading blanks so the 1st lands under its weekday (Monday-first).
    val leading = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
    val cells = leading + month.lengthOfMonth()
    val rows = (cells + 6) / 7

    Column(Modifier.fillMaxWidth()) {
        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val index = row * 7 + column
                    val dayOfMonth = index - leading + 1
                    if (dayOfMonth < 1 || dayOfMonth > month.lengthOfMonth()) {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = month.atDay(dayOfMonth)
                        DayCell(
                            date = date,
                            isFuture = date.isAfter(today),
                            isToday = date == today,
                            inSelection = inSelection(date, selectionStart, selectionEnd),
                            isSelectionEdge = date == selectionStart || date == selectionEnd,
                            earningWeight = maxGross?.let { max ->
                                (grossByDay[date] ?: 0.0).takeIf { it > 0.0 }?.let { (it / max) }
                            },
                            onPick = onPick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

private fun inSelection(date: LocalDate, start: LocalDate?, end: LocalDate?): Boolean {
    if (start == null) return false
    val last = end ?: start
    return !date.isBefore(start) && !date.isAfter(last)
}

@Composable
private fun DayCell(
    date: LocalDate,
    isFuture: Boolean,
    isToday: Boolean,
    inSelection: Boolean,
    isSelectionEdge: Boolean,
    earningWeight: Double?,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    val container = when {
        isSelectionEdge -> c.accent
        inSelection -> c.accentDim
        else -> null
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .let { if (container != null) it.background(container) else it }
            .let { if (isFuture) it else it.clickable(role = Role.Button) { onPick(date) } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    isFuture -> c.text3
                    isSelectionEdge -> c.accentText
                    isToday -> c.accent
                    else -> c.text
                },
                modifier = if (isFuture) Modifier.alpha(0.4f) else Modifier,
            )
            // The earning dot: present only on a day with recorded gross, brighter the more was
            // earned (floored so a small day is still visible rather than invisibly faint).
            if (earningWeight != null) {
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .alpha((0.35f + 0.65f * earningWeight.toFloat()).coerceIn(0f, 1f))
                        .background(if (isSelectionEdge) c.accentText else c.good),
                )
            }
        }
    }
}
