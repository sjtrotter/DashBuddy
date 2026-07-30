package cloud.trotter.dashbuddy.feature.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppHeatScale
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.domain.analytics.DayPlan
import cloud.trotter.dashbuddy.domain.analytics.DayPlanner
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.hourOfDayLabel
import cloud.trotter.dashbuddy.domain.format.hourRangeLabel
import cloud.trotter.dashbuddy.feature.dashboard.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** After this hour a recommended window is described as "tonight" rather than "today". */
private const val EVENING_HOUR = 17

/**
 * **Today's plan** (#977 / brief §2) — the 24-cell strip of *this weekday's* row of the driver's own
 * lifetime hour×day heatmap, the hours already spent dimmed, and the best still-available window
 * outlined with a one-line headline.
 *
 * **This is the driver's own record, not a promise.** Every figure is `Σ their frozen net ÷ Σ their
 * online hours` for that hour-of-week, and the card always says so verbatim — `from your own
 * <weekday>s, lifetime — not a guarantee` (brief §2's copy rule, §9's projection rule). When the
 * weekday is under [DayPlanner.MIN_SAMPLED_HOURS] recorded hours the card states the thin data
 * **instead of** a rate; it never smooths, never extrapolates, and never quotes a window it doesn't
 * have the history to stand behind.
 *
 * **Reactive (rules 1–4).** The state holds the anchors — the lifetime heatmap and today's date — and
 * this composable derives the time-dependent part: the current hour comes from the shared
 * [rememberNow] ticker, so crossing an hour boundary re-dims the strip and re-picks the window with
 * no state-machine transition and no reopening of the screen. The tick is funnelled through a
 * [derivedStateOf] on the hour, so 24 cells recompose once an hour rather than once a second.
 */
@Composable
fun TodayPlanCard(
    today: LocalDate,
    heatmap: EarningsHeatmap,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val c = AppTheme.colors
    val now: State<Long> = rememberNow()
    val currentHour by remember(zone) {
        derivedStateOf { Instant.ofEpochMilli(now.value).atZone(zone).hour }
    }
    val dayIndex = today.dayOfWeek.value - 1
    val plan = remember(heatmap, dayIndex, currentHour) {
        DayPlanner.plan(heatmap, dayIndex, currentHour)
    }
    // The strip is tinted against the driver's own best hour of the WHOLE week (the heatmap's own
    // SSOT), not against this weekday's best — so a weak weekday honestly reads as a weak weekday
    // instead of being re-normalised into looking like a good one.
    val maxRate = heatmap.maxDollarsPerHour ?: 0.0
    val weekdayPlural = weekdayPlural(dayIndex)

    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.dashboard_plan_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))

        PlanStrip(plan = plan, maxRate = maxRate)
        Spacer(Modifier.height(6.dp))
        HourAxis()
        Spacer(Modifier.height(12.dp))

        Text(
            text = headline(plan, weekdayPlural),
            style = MaterialTheme.typography.bodyMedium,
            color = if (plan.bestWindow != null) c.text else c.text2,
        )
        Spacer(Modifier.height(4.dp))
        // The provenance line is non-negotiable copy and is rendered on EVERY state of this card —
        // including the thin-data one, where it is what tells the driver whose (sparse) record the
        // "not enough yet" message is about.
        Text(
            text = stringResource(R.string.dashboard_plan_provenance_format, weekdayPlural),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
    }
}

/** 24 hour-cells: heat-tinted by the driver's own rate, dimmed once spent, outlined when picked. */
@Composable
private fun PlanStrip(plan: DayPlan, maxRate: Double) {
    val c = AppTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        plan.cells.forEach { cell ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(0.55f)
                    // Rule 1: this alpha is time-derived, so it moves with the ticker above.
                    .alpha(if (cell.passed) PASSED_ALPHA else 1f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AppHeatScale.cellColor(cell.dollarsPerHour, maxRate, c))
                    .then(
                        if (cell.inBestWindow) {
                            Modifier.border(1.dp, c.accent, RoundedCornerShape(2.dp))
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/** Four evenly-spaced clock ticks under the strip — the same axis vocabulary the Patterns grid uses. */
@Composable
private fun HourAxis() {
    val c = AppTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(0, 6, 12, 18).forEach { hour ->
            Text(
                text = hourOfDayLabel(hour),
                style = MaterialTheme.typography.labelSmall,
                color = c.text3,
            )
        }
        Text(text = hourOfDayLabel(0), style = MaterialTheme.typography.labelSmall, color = c.text3)
    }
}

/**
 * The headline, in the three honest states:
 *  - thin weekday → state the sample count, never a rate;
 *  - enough history but nothing contiguous left today → say so;
 *  - a real window → name it and quote the driver's own rate across it.
 */
@Composable
private fun headline(plan: DayPlan, weekdayPlural: String): String {
    if (!plan.hasEnoughHistory) {
        return stringResource(
            R.string.dashboard_plan_thin_data_format,
            weekdayPlural,
            Formats.commaInt(plan.sampledHours),
        )
    }
    val best = plan.bestWindow
        ?: return stringResource(R.string.dashboard_plan_no_window_format, weekdayPlural)
    val range = hourRangeLabel(best.startHour, best.endHourExclusive)
    val rate = Formats.money(best.dollarsPerHour)
    // "tonight" is only honest for an evening window — a lunchtime pick says "today".
    return if (best.startHour >= EVENING_HOUR) {
        stringResource(R.string.dashboard_plan_best_tonight_format, range, weekdayPlural, rate)
    } else {
        stringResource(R.string.dashboard_plan_best_today_format, range, weekdayPlural, rate)
    }
}

/**
 * "Mondays" — the weekday name in its recurring-plural form, which is the exact shape brief §2's copy
 * rule requires (`from your own Mondays, lifetime`).
 *
 * The name is localized by `java.time`; the pluralization is a separate one-argument string so a
 * translator can replace the English `+s` rule with their own rather than having it welded into code.
 */
@Composable
private fun weekdayPlural(dayIndex: Int): String {
    val name = java.time.DayOfWeek.of(dayIndex.coerceIn(0, 6) + 1)
        .getDisplayName(TextStyle.FULL, Locale.getDefault())
    return stringResource(R.string.dashboard_plan_weekday_plural_format, name)
}

/** Spent hours stay legible but visibly done — the brief's 35%. */
private const val PASSED_ALPHA = 0.35f
