package cloud.trotter.log.gig.ui.main.analytics.recap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.component.AppCard
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.analytics.ANALYTICS_MONEY_EPSILON
import cloud.trotter.log.gig.domain.analytics.recap.RecapComparison
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.format.formatMonthDay
import cloud.trotter.log.gig.domain.format.formatWeekdayShort
import cloud.trotter.log.gig.ui.components.HairlineDivider
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsUiState
import cloud.trotter.log.gig.ui.main.analytics.ShiftToCheck
import cloud.trotter.log.gig.ui.main.analytics.allCheckLines
import cloud.trotter.log.gig.ui.main.analytics.isLargeText
import cloud.trotter.log.gig.ui.main.analytics.landingRowOf
import cloud.trotter.log.gig.ui.main.analytics.windowRange
import java.time.DayOfWeek

/** The week's total for the switch, the comparison under it, and "Cash included" when the week holds cash. */
@Composable
internal fun RecapTotal(state: AnalyticsUiState) {
    val c = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(Formats.money(state.total), style = AppTheme.num.heroNum, color = c.text)
        state.comparison?.let { ComparisonLines(it) }
        if (state.economics.cash > ANALYTICS_MONEY_EPSILON) {
            Text(
                stringResource(R.string.recap_cash_included_format, Formats.money(state.economics.cash)),
                style = MaterialTheme.typography.bodyMedium,
                color = c.text2,
            )
        }
    }
}

/** "Mon to Sat $527.40" over "Last week, Mon to Sat $550.00". */
@Composable
private fun ComparisonLines(comparison: RecapComparison) {
    val span = daySpan(comparison.firstDay, comparison.lastDay)
    Text(
        stringResource(R.string.recap_comparison_this_format, span, Formats.money(comparison.thisWeek)),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.text,
    )
    Text(
        comparison.lastWeek?.let { stringResource(R.string.recap_comparison_last_format, span, Formats.money(it)) }
            ?: stringResource(R.string.recap_comparison_last_none_format, span),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.text2,
    )
}

/** "Mon to Sat", or "Mon" when the span is one day. */
@Composable
private fun daySpan(first: DayOfWeek, last: DayOfWeek): String =
    if (first == last) {
        formatWeekdayShort(first)
    } else {
        stringResource(R.string.recap_range_format, formatWeekdayShort(first), formatWeekdayShort(last))
    }

/**
 * Est. Profit per hour and per mile, this week's beside last week's. A missing figure is the em dash. The tiles open
 * nothing yet (their screens are their own tickets). Stacked at large text.
 */
@Composable
internal fun RecapTiles(state: AnalyticsUiState) {
    val e = state.economics
    val last = state.lastWeekEconomics
    val hours = Formats.decimal(e.totals.onlineDuration / MILLIS_PER_HOUR)
    val perHour = TileFigures(
        label = stringResource(R.string.shift_page_profit_per_hour),
        value = e.netPerHour?.let { Formats.money(it) } ?: EMPTY_VALUE,
        lines = listOf(
            stringResource(
                R.string.recap_tile_hours_shifts_format,
                hours,
                pluralStringResource(R.plurals.recap_shift_count, state.shiftCount, state.shiftCount),
            ),
            stringResource(R.string.recap_last_week_format, last.netPerHour?.let { Formats.money(it) } ?: EMPTY_VALUE),
        ),
    )
    val perMile = TileFigures(
        label = stringResource(R.string.recap_tile_profit_per_mile),
        value = e.netPerMile?.let { Formats.money(it) } ?: EMPTY_VALUE,
        lines = listOf(
            if (e.totals.miles > 0.0) stringResource(R.string.recap_tile_miles_format, Formats.decimal(e.totals.miles, 0)) else EMPTY_VALUE,
            stringResource(R.string.recap_last_week_format, last.netPerMile?.let { Formats.money(it) } ?: EMPTY_VALUE),
        ),
    )
    if (isLargeText()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(perHour, Modifier.fillMaxWidth())
            Tile(perMile, Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(perHour, Modifier.weight(1f))
            Tile(perMile, Modifier.weight(1f))
        }
    }
}

private data class TileFigures(val label: String, val value: String, val lines: List<String>)

@Composable
private fun Tile(figures: TileFigures, modifier: Modifier) {
    val c = AppTheme.colors
    AppCard(modifier) {
        Text(figures.label, style = MaterialTheme.typography.labelMedium, color = c.text2)
        Text(figures.value, style = AppTheme.num.lgNum, color = c.text)
        figures.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.text2) }
    }
}

/**
 * "1 shift to check", then one row per shift ("Wed Oct 28, DoorDash") with its reasons from `ShiftChecks`. A tap
 * opens the shift page at the row concerned ([landingRowOf]). Nothing when no shift needs a check.
 */
@Composable
internal fun RecapShiftsToCheck(shifts: List<ShiftToCheck>, onOpenShift: (String, String?) -> Unit) {
    if (shifts.isEmpty()) return
    val c = AppTheme.colors
    AppCard(Modifier.fillMaxWidth()) {
        Text(
            pluralStringResource(R.plurals.recap_checks_title, shifts.size, shifts.size),
            style = MaterialTheme.typography.labelMedium,
            color = c.warn,
        )
        shifts.forEachIndexed { index, item ->
            if (index > 0) HairlineDivider()
            val date = item.date
            val app = item.shift.platform.displayName
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpenShift(item.shift.sessionId, landingRowOf(item.checks)) }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    stringResource(R.string.recap_check_shift_format, formatWeekdayShort(date.dayOfWeek), formatMonthDay(date), app),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.text,
                )
                allCheckLines(item.checks, app).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = c.text2)
                }
            }
        }
    }
}

/** Shifts (the week's count) and Spending (expenses plus fuel). Neither opens anything yet. */
@Composable
internal fun RecapRows(state: AnalyticsUiState, showShifts: Boolean = true) {
    AppCard(Modifier.fillMaxWidth()) {
        if (showShifts) {
            RecapRow(stringResource(R.string.recap_row_shifts), Formats.commaInt(state.shiftCount))
            HairlineDivider()
        }
        RecapRow(stringResource(R.string.recap_row_spending), Formats.money(state.spending))
    }
}

@Composable
private fun RecapRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.text)
        Text(value, style = AppTheme.num.smNum, color = AppTheme.colors.text)
    }
}

/** No shift ever recorded: one line, no total, no chart. */
@Composable
internal fun RecapNoShiftsYet() {
    Text(
        stringResource(R.string.recap_no_shifts_yet),
        style = MaterialTheme.typography.bodyLarge,
        color = AppTheme.colors.text2,
    )
}

/** A week with no shift: "No shifts Oct 19 to 25", then last week's total in words. */
@Composable
internal fun RecapWeekOff(state: AnalyticsUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.recap_week_off_format, windowRange(state.week, state.today).orEmpty()),
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.text,
        )
        Text(
            stringResource(R.string.recap_last_week_format, Formats.money(state.lastWeekTotal)),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.text2,
        )
    }
}

private const val MILLIS_PER_HOUR = 3_600_000.0
