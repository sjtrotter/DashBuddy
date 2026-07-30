package cloud.trotter.dashbuddy.feature.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppStatTile
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.dashboard.R

/**
 * **So far today** (#977 / brief §2) — four figures off the existing rolling `TODAY`
 * [PeriodEconomics]: kept (net), net/hr, drops, miles.
 *
 * Replaces the old four-way period selector on Home (#657's `PeriodReview`): Home is "Today" now, and
 * every other window is reachable through the week block's `Recap →` and the Analytics pager. The
 * numbers are unchanged — the same frozen-net read, just no longer switchable here.
 *
 * "Kept" rather than "True Net" is deliberate plain-language copy (brief §4.1's rule, applied to
 * Home): the tile's subtitle carries the frozen-cost disclosure so the headline word doesn't have to.
 *
 * Reactive but not ticking: the economics flow re-emits on every projector commit and re-anchors at
 * local midnight, so the card is fresh without a clock of its own (the same reasoning #657 recorded —
 * a settled figure has nothing to tick).
 */
@Composable
fun SoFarToday(economics: PeriodEconomics, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.dashboard_today_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppStatTile(
                label = stringResource(R.string.dashboard_today_stat_kept),
                value = Formats.money(economics.netProfit),
                sub = stringResource(R.string.dashboard_today_stat_kept_sub),
                valueColor = if (economics.netProfit >= 0.0) c.good else c.bad,
                modifier = Modifier.weight(1f),
            )
            AppStatTile(
                label = stringResource(R.string.dashboard_today_stat_net_per_hour),
                value = economics.netPerHour?.let { Formats.money(it) } ?: EMPTY_VALUE,
                sub = stringResource(R.string.dashboard_today_stat_net_per_hour_sub),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppStatTile(
                label = stringResource(R.string.dashboard_today_stat_drops),
                value = Formats.commaInt(economics.totals.deliveries),
                modifier = Modifier.weight(1f),
            )
            AppStatTile(
                label = stringResource(R.string.dashboard_today_stat_miles),
                value = Formats.decimal(economics.totals.miles),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
