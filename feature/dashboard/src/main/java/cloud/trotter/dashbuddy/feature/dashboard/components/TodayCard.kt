package cloud.trotter.dashbuddy.feature.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatDuration
import cloud.trotter.dashbuddy.feature.dashboard.R
import java.time.LocalDate
import java.time.ZoneId

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
 * online sit under it as inline label/value pairs rather than four boxed [AppStatTile]s of equal
 * weight, so the card has a point instead of a grid. Their `sub` captions ("after real costs",
 * "while online") are gone with them (#1024 D3) — that vocabulary belongs to the screen's single
 * `How these numbers work` footer now, not to four captions the driver reads once and then never
 * again.
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
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
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

        Spacer(Modifier.height(12.dp))
        InlineFigures(economics)

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = c.line)
        Spacer(Modifier.height(12.dp))

        TodayPlanSection(today = today, heatmap = heatmap, zone = zone)
    }
}

/**
 * `Net/hr · Drops · Miles · Online` — the day's supporting figures, evenly weighted against each
 * other and all of them subordinate to the kept headline above.
 */
@Composable
private fun InlineFigures(economics: PeriodEconomics) {
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
        InlineFigure(
            label = stringResource(R.string.dashboard_today_stat_online),
            // An un-started day has no online span; "0s" would read as a measurement of nothing.
            value = economics.totals.onlineDuration
                .takeIf { it > 0L }
                ?.let { formatDuration(it) }
                ?: EMPTY_VALUE,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InlineFigure(label: String, value: String, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(modifier = modifier) {
        Text(text = value, style = AppTheme.num.mdNum, color = c.text)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = c.text3)
    }
}
