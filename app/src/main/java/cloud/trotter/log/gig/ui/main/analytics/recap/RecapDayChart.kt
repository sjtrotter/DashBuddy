package cloud.trotter.log.gig.ui.main.analytics.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.component.AppCard
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.analytics.recap.DayMark
import cloud.trotter.log.gig.domain.analytics.recap.RecapDay
import cloud.trotter.log.gig.domain.analytics.recap.RecapMeasure
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.format.formatWeekdayFull
import cloud.trotter.log.gig.domain.format.formatWeekdayShort

/** Test tag of a day row: `recap-day-<ISO date>`. */
internal fun dayRowTag(day: RecapDay): String = "recap-day-${day.date}"

private val BAR_HEIGHT = 12.dp
private val DOT_SIZE = 10.dp
private val MIN_ROW_HEIGHT = 48.dp

/**
 * Earned (or Est. Profit) by day (#1515): one horizontal bar per weekday from $0, its amount printed, a dot for the
 * same weekday last week. Today's bar is outlined, not filled. A day with no value keeps its row: "No shift", "No pay
 * found", or nothing for a day ahead, which still shows last week's dot. A day with a shift opens it ([tap]); any
 * other day is not clickable.
 */
@Composable
internal fun RecapDayChart(
    days: List<RecapDay>,
    lastWeek: List<RecapDay>,
    measure: RecapMeasure,
    onOpenDay: (DayTap) -> Unit,
    modifier: Modifier = Modifier,
) {
    val values = days.map { it.value(measure) } + lastWeek.map { it.value(measure) }
    val max = values.filterNotNull().maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
    AppCard(modifier.fillMaxWidth()) {
        Text(
            stringResource(if (measure == RecapMeasure.EARNED) R.string.recap_chart_earned_title else R.string.recap_chart_profit_title),
            style = MaterialTheme.typography.labelMedium,
            color = AppTheme.colors.text2,
        )
        Spacer(Modifier.height(4.dp))
        days.forEachIndexed { index, day ->
            DayRow(day, lastWeek.getOrNull(index), measure, max, onOpenDay)
        }
        Spacer(Modifier.height(8.dp))
        Legend()
    }
}

@Composable
private fun DayRow(day: RecapDay, lastWeek: RecapDay?, measure: RecapMeasure, max: Double, onOpenDay: (DayTap) -> Unit) {
    val c = AppTheme.colors
    val value = day.value(measure)
    val lastValue = lastWeek?.value(measure)
    val shown = when {
        value != null -> Formats.money(value)
        day.mark == DayMark.NO_SHIFT -> stringResource(R.string.recap_day_no_shift)
        day.mark == DayMark.NO_PAY_FOUND -> stringResource(R.string.recap_day_no_pay)
        else -> null
    }
    val description = listOfNotNull(
        formatWeekdayFull(day.date.dayOfWeek),
        shown,
        stringResource(R.string.recap_day_in_progress).takeIf { day.inProgress },
        lastWeek?.let { stringResource(R.string.recap_day_last_week_format, lastWeekText(it, measure)) },
    ).joinToString(", ")
    val tap = day.tap()
    val clickLabel = when (tap) {
        is DayTap.Shift -> stringResource(R.string.recap_day_open_shift)
        is DayTap.Day -> stringResource(R.string.recap_day_open_day)
        null -> null
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_ROW_HEIGHT)
            .testTag(dayRowTag(day))
            .then(if (tap != null) Modifier.clickable(onClickLabel = clickLabel, role = Role.Button) { onOpenDay(tap) } else Modifier)
            .clearAndSetSemantics { contentDescription = description }
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatWeekdayShort(day.date.dayOfWeek),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
            )
            if (shown != null) {
                Text(
                    shown,
                    style = if (value != null) AppTheme.num.smNum else MaterialTheme.typography.bodyMedium,
                    color = if (value != null) c.text else c.text2,
                )
            }
        }
        Bar(fraction = value?.let { (it / max).toFloat() }, inProgress = day.inProgress, dot = lastValue?.let { (it / max).toFloat() })
    }
}

/** Last week's same day as words: its amount, or its state. */
@Composable
private fun lastWeekText(day: RecapDay, measure: RecapMeasure): String = day.value(measure)?.let { Formats.money(it) }
    ?: when (day.mark) {
        DayMark.NO_PAY_FOUND -> stringResource(R.string.recap_day_no_pay)
        else -> stringResource(R.string.recap_day_no_shift)
    }

/** The track from $0, this week's bar ([fraction] null draws none), and last week's dot. */
@Composable
private fun Bar(fraction: Float?, inProgress: Boolean, dot: Float?) {
    val c = AppTheme.colors
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.line))
        if (fraction != null) {
            val shape = MaterialTheme.shapes.extraSmall
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .clip(shape)
                    .then(if (inProgress) Modifier.border(2.dp, c.accent, shape) else Modifier.background(c.accent)),
            )
        }
        if (dot != null) {
            val x = (maxWidth - DOT_SIZE) * dot.coerceIn(0f, 1f)
            Box(
                Modifier
                    .offset(x = x)
                    .size(DOT_SIZE)
                    .clip(CircleShape)
                    .background(c.text)
                    .border(1.dp, c.surface, CircleShape),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    val c = AppTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LegendItem(stringResource(R.string.analytics_window_this_week)) {
            Box(Modifier.width(16.dp).height(BAR_HEIGHT).clip(MaterialTheme.shapes.extraSmall).background(c.accent))
        }
        LegendItem(stringResource(R.string.analytics_window_last_week)) {
            Box(Modifier.size(DOT_SIZE).clip(CircleShape).background(c.text))
        }
        LegendItem(stringResource(R.string.recap_legend_in_progress)) {
            Box(Modifier.width(16.dp).height(BAR_HEIGHT).border(2.dp, c.accent, MaterialTheme.shapes.extraSmall))
        }
    }
}

@Composable
private fun LegendItem(label: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        swatch()
        Text(label, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.text2)
    }
}
