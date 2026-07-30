package cloud.trotter.dashbuddy.feature.dashboard.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppSparkline
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.NetDelta
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.dashboard.R

/**
 * **This week** (#977 / brief §2) — the pay week's kept money, how it compares with last week, a
 * per-day net sparkline, and a `Recap →` into the Analytics hub anchored on the same week.
 *
 * Every number is the read-model's own: net is the frozen per-delivery net (an economy edit never
 * rewrites it — §9), the sparkline plots [DailyEarnings.net] so a day whose miles ate it can't look
 * good, and the delta routes through the shared [NetDelta] rule, which states "no comparison" rather
 * than dividing by an empty week and reads a sub-half-percent wobble as flat rather than as a trend.
 *
 * Stateless, data-in / lambdas-out: nothing here ticks (a settled week's total has nothing to tick),
 * and the tap only raises [onOpenRecap] — the host owns what that means.
 */
@Composable
fun WeekRecapCard(
    economics: PeriodEconomics,
    previousEconomics: PeriodEconomics?,
    dailyEarnings: List<DailyEarnings>,
    onOpenRecap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    val recapLabel = stringResource(R.string.dashboard_week_recap_action)

    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = recapLabel, role = Role.Button) { onOpenRecap() },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dashboard_week_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.text3,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = Formats.money(economics.netProfit),
                    style = AppTheme.num.xlNum,
                    color = if (economics.netProfit >= 0.0) c.good else c.bad,
                )
            }
            Text(
                text = recapLabel,
                style = MaterialTheme.typography.labelMedium,
                color = c.accent,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = deltaText(economics, previousEconomics),
            style = MaterialTheme.typography.bodySmall,
            color = deltaColor(economics, previousEconomics),
        )

        // Kept money per day (§7.3). Hidden below two points: a one-point "line" is a shape with no
        // trend in it, which would read as information the week doesn't contain.
        if (dailyEarnings.size >= 2) {
            Spacer(Modifier.height(10.dp))
            AppSparkline(values = dailyEarnings.map { it.net }, color = c.accent)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.dashboard_week_frozen_note),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
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

/** "▲ 12% vs last week" and its honest alternatives. */
@Composable
private fun deltaText(economics: PeriodEconomics, previous: PeriodEconomics?): String {
    val delta = NetDelta.delta(economics.netProfit, previous?.netProfit)
    return when (delta.direction) {
        // A pay week always HAS a predecessor, so NONE can only mean the previous week's read has not
        // landed yet — say that rather than implying a comparison was made.
        NetDelta.Direction.NONE -> stringResource(R.string.dashboard_week_delta_none)
        NetDelta.Direction.FROM_ZERO -> stringResource(R.string.dashboard_week_delta_from_nothing)
        NetDelta.Direction.FLAT -> stringResource(R.string.dashboard_week_delta_flat)
        NetDelta.Direction.UP -> stringResource(
            R.string.dashboard_week_delta_up_format,
            Formats.percent(delta.fraction ?: 0.0),
        )
        NetDelta.Direction.DOWN -> stringResource(
            R.string.dashboard_week_delta_down_format,
            Formats.percent(kotlin.math.abs(delta.fraction ?: 0.0)),
        )
    }
}
