package cloud.trotter.dashbuddy.feature.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented
import cloud.trotter.dashbuddy.core.designsystem.component.AppStatTile
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.dashboard.R

/** Placeholder shown for a rate figure that has no measurable denominator yet. */
private const val EMPTY_VALUE = "—"

/** The review windows offered by the period selector, in display order. */
private data class PeriodOption(val period: AnalyticsPeriod, val label: String)

@Composable
private fun periodOptions(): List<PeriodOption> = listOf(
    PeriodOption(AnalyticsPeriod.TODAY, stringResource(R.string.common_period_today)),
    PeriodOption(AnalyticsPeriod.THIS_WEEK, stringResource(R.string.common_period_week)),
    PeriodOption(AnalyticsPeriod.THIS_MONTH, stringResource(R.string.common_period_month)),
    PeriodOption(AnalyticsPeriod.LIFETIME, stringResource(R.string.common_period_lifetime)),
)

/**
 * The primary home economics (#657): a Today / This week / Lifetime selector over the
 * read-model True Net · Net $/hr · Miles tiles. Frozen net (Σ each delivery's net against
 * its accepted cost basis + unattributed pay), so an economy edit never rewrites a past
 * period. Reactive but **not** live-ticking: the `economics` flow re-emits on each
 * projector commit (Room invalidation) and at midnight/week rollover, so the screen is
 * fresh-on-open without a `rememberNow()` clock — a historical period's $/hr is a fixed
 * value, so there is nothing to tick.
 */
@Composable
fun PeriodReview(
    selectedPeriod: AnalyticsPeriod,
    economics: PeriodEconomics,
    onSelectPeriod: (AnalyticsPeriod) -> Unit,
) {
    val periodOptions = periodOptions()
    val selectedLabel = periodOptions.first { it.period == selectedPeriod }.label
    AppSegmented(
        options = periodOptions.map { it.label },
        selected = selectedLabel,
        onSelect = { label ->
            periodOptions.firstOrNull { it.label == label }?.let { onSelectPeriod(it.period) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppStatTile(
            label = stringResource(R.string.dashboard_screen_stat_true_net),
            value = Formats.money(economics.netProfit),
            sub = selectedLabel,
            valueColor = if (economics.netProfit >= 0.0) AppTheme.colors.good else AppTheme.colors.bad,
            modifier = Modifier.weight(1f),
        )
        AppStatTile(
            label = stringResource(R.string.dashboard_screen_stat_net_per_hour),
            value = economics.netPerHour?.let { Formats.money(it) } ?: EMPTY_VALUE,
            sub = selectedLabel,
            modifier = Modifier.weight(1f),
        )
        AppStatTile(
            label = stringResource(R.string.dashboard_screen_stat_miles),
            value = Formats.decimal(economics.totals.miles),
            sub = selectedLabel,
            modifier = Modifier.weight(1f),
        )
    }
}
