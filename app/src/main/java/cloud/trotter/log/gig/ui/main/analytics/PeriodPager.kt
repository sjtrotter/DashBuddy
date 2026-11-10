package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindow
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindows
import cloud.trotter.log.gig.domain.analytics.WindowGranularity
import cloud.trotter.log.gig.domain.format.formatMonthDay
import cloud.trotter.log.gig.domain.format.formatMonthDayYear
import cloud.trotter.log.gig.domain.format.formatMonthName
import cloud.trotter.log.gig.domain.format.formatMonthYear
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** How a window sits relative to today (#970): the word in front of its range ("This week, Oct 26 to Nov 1"). */
enum class WindowRelation {
    /** The window containing today. */
    CURRENT,

    /** Exactly one step back from the current window. */
    PREVIOUS,

    /** Further back: the range itself is the whole label. */
    OLDER,
}

/** A window's range as display copy: [start], and [end] when the window spans more than one day. */
data class RangeParts(val start: String, val end: String?)

/**
 * Pure label logic for the period bar (#970): the relation word and the range's two ends. Compose-free, so the
 * calendar reasoning ("is this the current week?", "does this span two years?") is provable without rendering.
 * Every date string routes through the `:domain` date formats; the word joining the two ends is the screen's
 * (`recap_range_format`).
 */
object WindowLabel {

    fun relation(window: AnalyticsWindow, today: LocalDate): WindowRelation {
        if (window.isLifetime) return WindowRelation.CURRENT
        val current = AnalyticsWindows.current(window.granularity, today)
        return when (window.startDate) {
            current.startDate -> WindowRelation.CURRENT
            AnalyticsWindows.step(current, -1).startDate -> WindowRelation.PREVIOUS
            else -> WindowRelation.OLDER
        }
    }

    /**
     * The window's range, or `null` for Lifetime. The year is stated only when the window leaves [today]'s year, so
     * the common case stays short ("Jul 13" to "19") while a paged-back window is never ambiguous about its year.
     */
    fun range(window: AnalyticsWindow, today: LocalDate, locale: Locale = Locale.getDefault()): RangeParts? {
        val start = window.startDate ?: return null
        val end = window.endDateInclusive ?: return null
        val sameYearAsToday = start.year == today.year && end.year == today.year
        if (window.granularity == WindowGranularity.MONTH) {
            val month = YearMonth.from(start)
            return RangeParts(if (sameYearAsToday) formatMonthName(month, locale) else formatMonthYear(month, locale), null)
        }
        return dates(start, end, today, locale)
    }

    /**
     * A span of dates, [start] to [end] inclusive, under the same rules as a week's range: "Oct 26" to "Nov 1", "Oct 1"
     * to "4" inside one month, the one date of a one-day span, the year only when the span leaves [today]'s year.
     */
    fun dates(start: LocalDate, end: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): RangeParts {
        val sameYearAsToday = start.year == today.year && end.year == today.year
        return when {
            start == end ->
                RangeParts(if (sameYearAsToday) formatMonthDay(start, locale) else formatMonthDayYear(start, locale), null)
            start.year != end.year -> RangeParts(formatMonthDayYear(start, locale), formatMonthDayYear(end, locale))
            !sameYearAsToday -> RangeParts(formatMonthDay(start, locale), formatMonthDayYear(end, locale))
            start.month == end.month -> RangeParts(formatMonthDay(start, locale), end.dayOfMonth.toString())
            else -> RangeParts(formatMonthDay(start, locale), formatMonthDay(end, locale))
        }
    }
}

/** "Oct 26 to Nov 1", or the one date of a one-day window. */
@Composable
internal fun windowRange(window: AnalyticsWindow, today: LocalDate): String? = WindowLabel.range(window, today)?.let { rangeText(it) }

/** "Oct 1 to 4", "Oct 27 to 31", or "Nov 30" alone: a span of dates as the period bar prints a week. */
@Composable
internal fun datesRange(start: LocalDate, end: LocalDate, today: LocalDate): String = rangeText(WindowLabel.dates(start, end, today))

@Composable
private fun rangeText(parts: RangeParts): String =
    parts.end?.let { stringResource(R.string.recap_range_format, parts.start, it) } ?: parts.start

/** "This week, Oct 26 to Nov 1", "Last week, Oct 19 to 25", or the range alone for an older week. */
@Composable
internal fun weekTitle(week: AnalyticsWindow, today: LocalDate): String {
    val range = windowRange(week, today).orEmpty()
    val word = when (WindowLabel.relation(week, today)) {
        WindowRelation.CURRENT -> stringResource(R.string.analytics_window_this_week)
        WindowRelation.PREVIOUS -> stringResource(R.string.analytics_window_last_week)
        WindowRelation.OLDER -> return range
    }
    return stringResource(R.string.recap_period_format, word, range)
}

/**
 * Recap's period bar (#1515): "‹ This week, Oct 26 to Nov 1 ›". Recap is the only screen that pages the week. `›` is
 * disabled at this week. Stateless: state down, the step up.
 */
@Composable
internal fun RecapPeriodBar(
    week: AnalyticsWindow,
    today: LocalDate,
    canStepForward: Boolean,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) = PeriodBar(
    label = weekTitle(week, today),
    previousDescription = stringResource(R.string.recap_week_previous),
    nextDescription = stringResource(R.string.recap_week_next),
    canStepForward = canStepForward,
    onStep = onStep,
    modifier = modifier,
)

/**
 * The period bar's look, "‹ label ›": Recap's week and the Month screen's month. `‹` steps back (`-1`), `›` forward
 * (`1`) and is disabled when [canStepForward] is false. Stateless.
 */
@Composable
internal fun PeriodBar(
    label: String,
    previousDescription: String,
    nextDescription: String,
    canStepForward: Boolean,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onStep(-1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = previousDescription)
        }
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.text,
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { onStep(1) }, enabled = canStepForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = nextDescription)
        }
    }
}
