package cloud.trotter.dashbuddy.feature.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatDuration
import cloud.trotter.dashbuddy.feature.dashboard.R
import java.time.LocalDate

/**
 * **Today** (#1024 section D1) — ONE card for the whole day: what the day has kept so far, the four
 * figures behind it, then a hairline and the plan for the hours still ahead.
 *
 * It is the merge of #977's two separate cards (`SoFarToday` + `TodayPlanCard`). Both were about
 * today, so two cards said so twice, and the repetition was the point of #1024: *one number, one
 * place*. Nothing was re-derived in the merge — the record half reads the same rolling `TODAY`
 * [PeriodEconomics] and the plan half runs the same `DayPlanner` over the same lifetime heatmap
 * ([TodayPlanSection]); only the containers changed.
 *
 * **Hierarchy, not equality.** Kept money is the card's one large figure; net/hr · drops · miles ·
 * online sit under it as inline label/value pairs rather than four boxed `AppStatTile`s of equal
 * weight, so the card has a point instead of a grid. Their `sub` captions ("after real costs",
 * "while online") are gone with them (#1024 D3) — that vocabulary belongs to the screen's single
 * `How these numbers work` footer now. The "while online" denominator did NOT go with the caption:
 * it moved into the figure's own label, because a rate whose denominator is only findable inside a
 * collapsed footer reads as an all-day rate, which it is not.
 *
 * §9 honesty is unchanged: a null `netPerHour` renders [EMPTY_VALUE], never `$0.00/hr` (#936's
 * discipline applied to a read surface), an un-dashed day shows the same em dash for online rather
 * than a fabricated `0s`, and every state of the plan half still states its own reason.
 */
@Composable
fun TodayCard(
    economics: PeriodEconomics,
    today: LocalDate,
    heatmap: EarningsHeatmap,
    sessionStartedAt: Long?,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.dashboard_today_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = Formats.money(economics.netProfit),
            style = AppTheme.num.xlNum,
            color = if (economics.netProfit >= 0.0) c.good else c.bad,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.dashboard_today_stat_kept),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )

        Spacer(Modifier.height(ROW_GAP))
        InlineFigures(economics = economics, sessionStartedAt = sessionStartedAt)

        Hairline()

        TodayPlanSection(today = today, heatmap = heatmap)
    }
}

/**
 * `Net/hr online · Drops · Miles · Online` — the day's supporting figures, evenly weighted against
 * each other and all of them subordinate to the kept headline above.
 */
@Composable
private fun InlineFigures(economics: PeriodEconomics, sessionStartedAt: Long?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InlineFigure(
            label = stringResource(R.string.dashboard_today_stat_net_per_hour),
            // Null is "not measurable yet", which an em dash says and `$0.00` lies about.
            value = economics.netPerHour?.let { Formats.money(it) } ?: EMPTY_VALUE,
            modifier = Modifier.weight(1f),
        )
        InlineFigure(
            label = stringResource(R.string.dashboard_today_stat_drops),
            value = Formats.commaInt(economics.totals.deliveries),
            modifier = Modifier.weight(1f),
        )
        InlineFigure(
            label = stringResource(R.string.dashboard_today_stat_miles),
            value = Formats.decimal(economics.totals.miles),
            modifier = Modifier.weight(1f),
        )
        OnlineFigure(
            onlineMillis = economics.totals.onlineDuration,
            sessionStartedAt = sessionStartedAt,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Online time, in the only two shapes that are true (#1024 review item 12 — Reactive UI rule 1).
 *
 * The read-model's `onlineDuration` is a **settled sum** over the day's sessions, recomputed when the
 * projector folds an event. While a dash is running that makes it a live value that does not tick:
 * a driver idling between offers watches it sit still for as long as nothing folds, which is exactly
 * the "frozen-looking value" rule 5 calls a defect. There is no live-total anchor to derive from
 * either — adding the elapsed tail to the settled sum would double-count the part already folded.
 *
 * So the figure changes what it *is* rather than pretending: with a dash active it reports the
 * current dash's elapsed time from the session's own `startedAt` anchor, ticking through
 * [rememberNow] (rule 2 — the state holds the anchor, the composable derives the value), under a
 * label that says so. With no dash running there is nothing live to be stale about, so it reports
 * the day's settled total. A dash that has just started reads `0s` and immediately counts, instead
 * of the em dash a zero-duration settled read would have shown for its first minutes.
 *
 * The ticker read is deliberately inside this leaf composable: Compose invalidates the scope that
 * read the state, so the per-second tick recomposes one `Text`, not the card.
 */
@Composable
private fun OnlineFigure(onlineMillis: Long, sessionStartedAt: Long?, modifier: Modifier = Modifier) {
    if (sessionStartedAt != null) {
        val now by rememberNow()
        InlineFigure(
            label = stringResource(R.string.dashboard_today_stat_on_dash),
            value = formatDuration(now - sessionStartedAt),
            modifier = modifier,
        )
        return
    }
    InlineFigure(
        label = stringResource(R.string.dashboard_today_stat_online),
        // A day with no dash has no online span; "0s" would read as a measurement of nothing.
        value = onlineMillis.takeIf { it > 0L }?.let { formatDuration(it) } ?: EMPTY_VALUE,
        modifier = modifier,
    )
}

@Composable
private fun InlineFigure(label: String, value: String, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(modifier = modifier) {
        Text(text = value, style = AppTheme.num.mdNum, color = c.text)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = c.text3)
    }
}
