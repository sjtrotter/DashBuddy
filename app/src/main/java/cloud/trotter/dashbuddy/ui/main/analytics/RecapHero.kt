package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppSparkline
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindow
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindows
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatDuration
import java.time.LocalDate

/**
 * Pure copy logic for the recap hero (#970 / brief §3.3) — Compose-free so the comparison rules are
 * unit-testable away from rendering (the [WaterfallModel] precedent).
 *
 * The rules exist to keep §9's honesty bar: a delta is only stated when there is something real to
 * compare against. No previous window (Lifetime) says so; a previous window that earned nothing gets
 * a worded statement rather than a divide-by-zero percentage; a sub-half-percent move reads as
 * "the same" rather than as a spurious `▲ 0%`.
 */
object RecapModel {

    /** Below this a money figure is effectively zero — the same threshold the hub's callouts use. */
    private const val MONEY_EPSILON = UNATTRIBUTED_EPSILON

    /** Below this magnitude a relative move is noise, not a trend. */
    private const val FLAT_FRACTION = 0.005

    enum class Direction {
        /** There is no previous equivalent window (Lifetime) — state that, compare nothing. */
        NONE,

        /** The previous window earned (effectively) nothing, so a percentage would divide by zero. */
        FROM_ZERO,

        /** Materially unchanged. */
        FLAT,
        UP,
        DOWN,
    }

    /** [fraction] is the signed relative change, present only for [Direction.UP]/[Direction.DOWN]. */
    data class Delta(val direction: Direction, val fraction: Double?)

    /**
     * The delta of [currentNet] against [previousNet] (the previous equivalent window's frozen net).
     * A null [previousNet] means "no such window".
     */
    fun delta(currentNet: Double, previousNet: Double?): Delta {
        if (previousNet == null) return Delta(Direction.NONE, null)
        if (kotlin.math.abs(previousNet) < MONEY_EPSILON) {
            return if (kotlin.math.abs(currentNet) < MONEY_EPSILON) {
                Delta(Direction.FLAT, null)
            } else {
                Delta(Direction.FROM_ZERO, null)
            }
        }
        val fraction = (currentNet - previousNet) / kotlin.math.abs(previousNet)
        return when {
            fraction > FLAT_FRACTION -> Delta(Direction.UP, fraction)
            fraction < -FLAT_FRACTION -> Delta(Direction.DOWN, fraction)
            else -> Delta(Direction.FLAT, null)
        }
    }

    /** True when the window recorded nothing at all — the hero says so instead of printing zeros. */
    fun isEmpty(economics: PeriodEconomics): Boolean =
        economics.totals.deliveries == 0 &&
            economics.totals.onlineDuration == 0L &&
            kotlin.math.abs(economics.grossEarnings) < MONEY_EPSILON
}

/**
 * The recap hero (#970 / brief §3.3) — the window's **kept** money, its delta against the previous
 * equivalent window, a per-day net sparkline, and one factual summary line. Sits above the tabs, so
 * every tab is read as a detail view of this one window.
 *
 * Copy discipline (§9): the headline is net and is labelled as **frozen** at decision-time costs; the
 * summary line states measured facts (gross, deliveries, online time, acceptance) and makes no
 * projection; a delta is stated only when a real previous window exists.
 *
 * Stateless and data-in — no clock: every figure here is a settled historical value (Reactive UI
 * rule 1's "would the dasher believe a stale value?" is answered by the fact that none of these tick).
 */
@Composable
fun RecapHero(
    window: AnalyticsWindow,
    today: LocalDate,
    economics: PeriodEconomics,
    previousEconomics: PeriodEconomics?,
    decisions: DecisionEconomics,
    dailyEarnings: List<DailyEarnings>,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    val netColor = if (economics.netProfit >= 0.0) c.good else c.bad

    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.analytics_hero_kept_label),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = Formats.money(economics.netProfit), style = AppTheme.num.heroNum, color = netColor)
        Spacer(Modifier.height(6.dp))
        Text(
            text = deltaText(window, today, economics, previousEconomics),
            style = MaterialTheme.typography.bodySmall,
            color = deltaColor(economics, previousEconomics),
        )

        // Sparkline of KEPT money per day (§7.3) — hidden when the window has no meaningful day axis
        // (a single day, Lifetime, or an over-long custom span all return an empty list).
        if (dailyEarnings.size >= 2) {
            Spacer(Modifier.height(10.dp))
            AppSparkline(values = dailyEarnings.map { it.net }, color = c.accent)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = summaryLine(economics, decisions),
            style = MaterialTheme.typography.bodySmall,
            color = c.text2,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.analytics_hero_frozen_note),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
    }
}

@Composable
private fun deltaColor(economics: PeriodEconomics, previous: PeriodEconomics?) =
    when (RecapModel.delta(economics.netProfit, previous?.netProfit).direction) {
        RecapModel.Direction.UP, RecapModel.Direction.FROM_ZERO -> AppTheme.colors.good
        RecapModel.Direction.DOWN -> AppTheme.colors.bad
        RecapModel.Direction.FLAT, RecapModel.Direction.NONE -> AppTheme.colors.text3
    }

/** "▲ 12% vs Jul 6 – 12" and friends — the previous window is named by its RANGE, never by a guess. */
@Composable
private fun deltaText(
    window: AnalyticsWindow,
    today: LocalDate,
    economics: PeriodEconomics,
    previous: PeriodEconomics?,
): String {
    val delta = RecapModel.delta(economics.netProfit, previous?.netProfit)
    val previousLabel = AnalyticsWindows.previous(window)
        ?.let { WindowLabel.range(it, today) }
        ?: return stringResource(R.string.analytics_hero_delta_none)
    return when (delta.direction) {
        RecapModel.Direction.NONE -> stringResource(R.string.analytics_hero_delta_none)
        RecapModel.Direction.FROM_ZERO ->
            stringResource(R.string.analytics_hero_delta_from_nothing_format, previousLabel)
        RecapModel.Direction.FLAT ->
            stringResource(R.string.analytics_hero_delta_flat_format, previousLabel)
        RecapModel.Direction.UP -> stringResource(
            R.string.analytics_hero_delta_up_format,
            Formats.percent(delta.fraction ?: 0.0),
            previousLabel,
        )
        RecapModel.Direction.DOWN -> stringResource(
            R.string.analytics_hero_delta_down_format,
            Formats.percent(kotlin.math.abs(delta.fraction ?: 0.0)),
            previousLabel,
        )
    }
}

/** "$412.83 gross · 47 deliveries · 15h 49m online · 37% acceptance" — measured facts only. */
@Composable
private fun summaryLine(economics: PeriodEconomics, decisions: DecisionEconomics): String {
    if (RecapModel.isEmpty(economics)) return stringResource(R.string.analytics_hero_no_data)
    val deliveryWord = if (economics.totals.deliveries == 1) {
        stringResource(R.string.time_tab_delivery_singular)
    } else {
        stringResource(R.string.time_tab_delivery_plural)
    }
    val parts = buildList {
        add(stringResource(R.string.analytics_hero_summary_gross_format, Formats.money(economics.grossEarnings)))
        add(
            stringResource(
                R.string.analytics_hero_summary_deliveries_format,
                Formats.commaInt(economics.totals.deliveries),
                deliveryWord,
            ),
        )
        if (economics.totals.onlineDuration > 0L) {
            add(
                stringResource(
                    R.string.analytics_hero_summary_online_format,
                    formatDuration(economics.totals.onlineDuration),
                ),
            )
        }
        decisions.acceptanceRate?.let {
            add(stringResource(R.string.analytics_hero_summary_acceptance_format, Formats.percent(it)))
        }
    }
    return parts.joinToString(stringResource(R.string.analytics_hero_summary_separator))
}
