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
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindow
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindows
import cloud.trotter.dashbuddy.domain.analytics.NetDelta
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatDuration
import java.time.LocalDate

/**
 * The recap hero (#970 / brief §3.3) — the window's **kept** money, its delta against the previous
 * equivalent window, and ONE facts line. Sits above the tabs, so every tab is read as a detail view
 * of this one window.
 *
 * **#1024 section A stripped it to those three things.** It used to be a card with a sparkline and a
 * frozen-cost footnote, and each of those repeated something one scroll below it: the sparkline plots
 * the same per-day series the Money tab's day chart draws (strictly less informative — no axis, no
 * tap), the footnote is one of nine copies of the frozen-cost disclosure now owned by the screen's
 * single `HowNumbersWorkFooter`, and the summary line led with GROSS, which is literally the first
 * clause of the money card's own headline. What is left is the one figure this hub exists to state
 * plus the measured denominators nothing else on the screen owns any more (deliveries and miles came
 * off the Money tab's rate tiles in the same pass). Losing the card container is the point too: a
 * hero inside the same bordered surface as everything below it reads as one more card.
 *
 * Copy discipline (§9): the headline is net — frozen at decision-time costs, as the screen footer
 * states once; the facts line states measured facts and makes no projection; a delta is stated only
 * when a real previous window exists, and when none does the hub SAYS so
 * (`analytics_hero_delta_none`) rather than rendering an empty space where a comparison would go.
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
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    val netColor = if (economics.netProfit >= 0.0) c.good else c.bad

    Column(modifier = modifier.fillMaxWidth()) {
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
        Spacer(Modifier.height(8.dp))
        Text(
            text = factsLine(economics, previousEconomics),
            style = MaterialTheme.typography.bodySmall,
            color = c.text2,
        )
    }
}

@Composable
private fun deltaColor(economics: PeriodEconomics, previous: PeriodEconomics?) =
    when (NetDelta.delta(economics.netProfit, previous?.netProfit).direction) {
        NetDelta.Direction.UP, NetDelta.Direction.FROM_ZERO -> AppTheme.colors.good
        NetDelta.Direction.DOWN -> AppTheme.colors.bad
        NetDelta.Direction.FLAT, NetDelta.Direction.NONE -> AppTheme.colors.text3
    }

/** "▲ 12% vs Jul 6 – 12" and friends — the previous window is named by its RANGE, never by a guess. */
@Composable
private fun deltaText(
    window: AnalyticsWindow,
    today: LocalDate,
    economics: PeriodEconomics,
    previous: PeriodEconomics?,
): String {
    val delta = NetDelta.delta(economics.netProfit, previous?.netProfit)
    val previousLabel = AnalyticsWindows.previous(window)
        ?.let { WindowLabel.range(it, today) }
        ?: return stringResource(R.string.analytics_hero_delta_none)
    return when (delta.direction) {
        NetDelta.Direction.NONE -> stringResource(R.string.analytics_hero_delta_none)
        NetDelta.Direction.FROM_ZERO ->
            stringResource(R.string.analytics_hero_delta_from_nothing_format, previousLabel)
        NetDelta.Direction.FLAT ->
            stringResource(R.string.analytics_hero_delta_flat_format, previousLabel)
        NetDelta.Direction.UP -> stringResource(
            R.string.analytics_hero_delta_up_format,
            Formats.percent(delta.fraction ?: 0.0),
            previousLabel,
        )
        NetDelta.Direction.DOWN -> stringResource(
            R.string.analytics_hero_delta_down_format,
            Formats.percent(kotlin.math.abs(delta.fraction ?: 0.0)),
            previousLabel,
        )
    }
}

/**
 * "47 deliveries · 15h 49m online · 208.6 mi · vs $264.10 the window before" — measured facts only,
 * and the ONLY place on the screen each of them appears (#1024 rule 1).
 *
 * What left this line and why: **gross** is the first clause of the money card's own headline
 * ("$412.83 came in."), and **acceptance** is the Offers tab's funnel — restating either here made
 * the hub say the same number twice on one scroll. What arrived: **miles**, which used to sit in the
 * Money tab's rate tiles beside the deliveries count, and the previous window's kept figure, so the
 * delta above resolves to an actual dollar amount rather than a bare percentage.
 *
 * Each clause is omitted when its measurement doesn't exist — no online time logged, no miles, no
 * predecessor worth comparing against — rather than rendered as a zero, and a window with nothing in
 * it says exactly that (§9).
 *
 * **The comparison clause needs a non-EMPTY predecessor, not merely a non-null one** (review F2).
 * `previousEconomics` is null only for Lifetime; a week the driver did not work still arrives as a
 * real `PeriodEconomics` full of zeros, and `vs $0.00 the window before` reads as a measurement of a
 * worked week rather than the absence of one — while the delta line above has already said "Up from
 * nothing in …" in words. [NetDelta.isEmpty] is the same predicate this function's own empty-window
 * branch uses, so the two can't disagree about what "nothing recorded" means.
 */
@Composable
private fun factsLine(economics: PeriodEconomics, previous: PeriodEconomics?): String {
    if (NetDelta.isEmpty(economics)) return stringResource(R.string.analytics_hero_no_data)
    val deliveryWord = if (economics.totals.deliveries == 1) {
        stringResource(R.string.time_tab_delivery_singular)
    } else {
        stringResource(R.string.time_tab_delivery_plural)
    }
    val parts = buildList {
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
        if (economics.totals.miles > 0.0) {
            add(
                stringResource(
                    R.string.analytics_hero_summary_miles_format,
                    Formats.decimal(economics.totals.miles),
                ),
            )
        }
        previous?.takeIf { !NetDelta.isEmpty(it) }?.let {
            add(
                stringResource(
                    R.string.analytics_hero_summary_previous_format,
                    Formats.money(it.netProfit),
                ),
            )
        }
    }
    return parts.joinToString(stringResource(R.string.analytics_hero_summary_separator))
}
