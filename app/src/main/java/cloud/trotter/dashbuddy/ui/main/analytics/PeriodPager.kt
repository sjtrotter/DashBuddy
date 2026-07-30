package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindow
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindows
import cloud.trotter.dashbuddy.domain.analytics.WindowGranularity
import cloud.trotter.dashbuddy.domain.format.formatMonthDay
import cloud.trotter.dashbuddy.domain.format.formatMonthDayYear
import cloud.trotter.dashbuddy.domain.format.formatMonthName
import cloud.trotter.dashbuddy.domain.format.formatMonthYear
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * How the selected window sits relative to "now" (#970) — the word in front of the range
 * ("This week · Jul 13–19"). Pure, so the label logic is unit-testable away from Compose.
 */
enum class WindowRelation {
    /** The window containing today. */
    CURRENT,

    /** Exactly one step back from the current window. */
    PREVIOUS,

    /** Further back, or a custom span — the range itself is the whole label. */
    OLDER,
}

/**
 * Pure label logic for the period pager (#970 / brief §3.1) — the relation word and the range text.
 *
 * Compose-free on purpose (the [MoneyWentModel]/[MileageTaxModel] precedent): the interesting part is
 * the *calendar* reasoning ("is this the current week?", "does this span two years?"), and it should
 * be provable without rendering. Every rendered string routes through the `:domain` date-format SSOT.
 */
object WindowLabel {

    /** En dash with hair spaces — a range separator, not a hyphen. */
    private const val RANGE_SEPARATOR = " – "

    /**
     * Where [window] sits relative to [today]. A CUSTOM span is always [WindowRelation.OLDER]: it has
     * no natural "this"/"last" reading even when it happens to contain today, and claiming one would
     * put a word in front of a range the driver drew themselves.
     */
    fun relation(window: AnalyticsWindow, today: LocalDate): WindowRelation {
        if (window.isLifetime) return WindowRelation.CURRENT
        if (window.granularity == WindowGranularity.CUSTOM) return WindowRelation.OLDER
        val current = AnalyticsWindows.current(window.granularity, today)
        return when (window.startDate) {
            current.startDate -> WindowRelation.CURRENT
            AnalyticsWindows.step(current, -1).startDate -> WindowRelation.PREVIOUS
            else -> WindowRelation.OLDER
        }
    }

    /**
     * The window's date range as display copy, or `null` for Lifetime (which has no range — its
     * title carries the whole meaning).
     *
     * The year is stated only when the window leaves [today]'s year, so the common case stays short
     * ("Jul 13 – 19") while a paged-back window is never ambiguous about which year it means.
     */
    fun range(window: AnalyticsWindow, today: LocalDate, locale: Locale = Locale.getDefault()): String? {
        val start = window.startDate ?: return null
        val end = window.endDateInclusive ?: return null
        val sameYearAsToday = start.year == today.year && end.year == today.year
        return when {
            window.granularity == WindowGranularity.MONTH -> {
                val month = YearMonth.from(start)
                if (sameYearAsToday) formatMonthName(month, locale) else formatMonthYear(month, locale)
            }
            start == end ->
                if (sameYearAsToday) formatMonthDay(start, locale) else formatMonthDayYear(start, locale)
            start.year != end.year ->
                formatMonthDayYear(start, locale) + RANGE_SEPARATOR + formatMonthDayYear(end, locale)
            !sameYearAsToday ->
                formatMonthDay(start, locale) + RANGE_SEPARATOR + formatMonthDayYear(end, locale)
            start.month == end.month ->
                formatMonthDay(start, locale) + RANGE_SEPARATOR + end.dayOfMonth.toString()
            else ->
                formatMonthDay(start, locale) + RANGE_SEPARATOR + formatMonthDay(end, locale)
        }
    }
}

/** The relation word for [window] — "This week", "Last month", or the range itself for older spans. */
@Composable
fun windowTitle(window: AnalyticsWindow, today: LocalDate): String {
    if (window.isLifetime) return stringResource(R.string.analytics_window_lifetime)
    val relation = WindowLabel.relation(window, today)
    val titleRes = when (window.granularity) {
        WindowGranularity.DAY -> when (relation) {
            WindowRelation.CURRENT -> R.string.analytics_window_today
            WindowRelation.PREVIOUS -> R.string.analytics_window_yesterday
            WindowRelation.OLDER -> null
        }
        WindowGranularity.WEEK -> when (relation) {
            WindowRelation.CURRENT -> R.string.analytics_window_this_week
            WindowRelation.PREVIOUS -> R.string.analytics_window_last_week
            WindowRelation.OLDER -> null
        }
        WindowGranularity.MONTH -> when (relation) {
            WindowRelation.CURRENT -> R.string.analytics_window_this_month
            WindowRelation.PREVIOUS -> R.string.analytics_window_last_month
            WindowRelation.OLDER -> null
        }
        WindowGranularity.CUSTOM -> R.string.analytics_window_custom
        WindowGranularity.LIFETIME -> R.string.analytics_window_lifetime
    }
    return titleRes?.let { stringResource(it) }
        ?: WindowLabel.range(window, today).orEmpty()
}

/** The granularity chips, paired with their resolved label (#428 Half A — identity stays the enum). */
private data class GranularityOption(val granularity: WindowGranularity, val label: String)

@Composable
private fun granularityOptions(): List<GranularityOption> = listOf(
    GranularityOption(WindowGranularity.DAY, stringResource(R.string.analytics_granularity_day)),
    GranularityOption(WindowGranularity.WEEK, stringResource(R.string.analytics_granularity_week)),
    GranularityOption(WindowGranularity.MONTH, stringResource(R.string.analytics_granularity_month)),
    GranularityOption(WindowGranularity.LIFETIME, stringResource(R.string.analytics_granularity_lifetime)),
    GranularityOption(WindowGranularity.CUSTOM, stringResource(R.string.analytics_granularity_custom)),
)

/**
 * The period control (#970 / brief §3.1) — a `‹ label ›` pager over a granularity chip row, replacing
 * the old four-value segmented period selector.
 *
 * `›` is disabled at the current window (there is no data in the future to page into), `‹` for
 * Lifetime (all time has nowhere to go). Tapping the centre label opens the range picker, as does the
 * `Custom…` chip. Stateless and data-in/lambdas-out — every decision was made in the ViewModel.
 */
@Composable
fun PeriodPager(
    window: AnalyticsWindow,
    today: LocalDate,
    canStepBack: Boolean,
    canStepForward: Boolean,
    onStep: (Int) -> Unit,
    onOpenPicker: () -> Unit,
    onSelectGranularity: (WindowGranularity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    val title = windowTitle(window, today)
    val range = WindowLabel.range(window, today)
    val openPickerLabel = stringResource(R.string.analytics_window_open_picker)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onStep(-1) }, enabled = canStepBack) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.analytics_window_previous),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = openPickerLabel, role = Role.Button) { onOpenPicker() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.text,
                    textAlign = TextAlign.Center,
                )
                // Lifetime has no range, and an OLDER window already shows its range as the title —
                // never print the same string twice.
                if (range != null && range != title) {
                    Text(
                        text = range,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.text3,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            IconButton(onClick = { onStep(1) }, enabled = canStepForward) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.analytics_window_next),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val options = granularityOptions()
        val selectedLabel = options.first { it.granularity == window.granularity }.label
        AppSegmented(
            options = options.map { it.label },
            selected = selectedLabel,
            onSelect = { label ->
                // Selection stays keyed off the enum (#428 Half A) — never a raw string comparison.
                val picked = options.firstOrNull { it.label == label }?.granularity ?: return@AppSegmented
                if (picked == WindowGranularity.CUSTOM) onOpenPicker() else onSelectGranularity(picked)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
