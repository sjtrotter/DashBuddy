package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppStatTile
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.analytics.GapStats
import cloud.trotter.dashbuddy.domain.analytics.NetPerHourPair
import cloud.trotter.dashbuddy.domain.analytics.PayMix
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatDuration

internal data class Figure(val label: String, val value: String, val sub: String? = null)

/** Views of the existing interpretation owners; no separate analytics assembly. */
@Composable
internal fun TierOneFigures(figures: List<Figure>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        figures.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { figure ->
                    AppStatTile(figure.label, figure.value, Modifier.weight(1f), sub = figure.sub)
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun moneyFigures(economics: PeriodEconomics, payMix: PayMix): List<Figure> {
    val split = MoneyWentModel.from(economics)
    val figures = mutableListOf(
        Figure(stringResource(R.string.analytics_tier1_earned), Formats.money(split.cameIn)),
        Figure(
            stringResource(R.string.money_tab_where_went_segment_car),
            Formats.money(split.carCosts),
            if (split.miles > 0.0) stringResource(
                R.string.analytics_hero_summary_miles_format,
                Formats.decimal(split.miles),
            ) else stringResource(R.string.time_tab_no_miles_measured_yet),
        ),
        Figure(
            stringResource(R.string.money_tab_stat_net_per_hour),
            economics.netPerHour?.let { "${Formats.money(it)}/hr" } ?: EMPTY_VALUE,
        ),
    )
    val share = payMix.tipShare
    if (share != null || payMix.hasBreakdown) {
        figures += Figure(
            stringResource(R.string.money_tab_pay_mix_segment_tips),
            when {
                share == null -> EMPTY_VALUE
                !payMix.breakdownComplete -> stringResource(R.string.analytics_tier1_at_least_format, Formats.percent(share))
                else -> Formats.percent(share)
            },
            when {
                !payMix.breakdownComplete -> stringResource(
                    R.string.money_tab_pay_mix_partial_coverage_format,
                    Formats.commaInt(payMix.deliveriesWithBreakdown), Formats.commaInt(payMix.deliveries),
                )
                else -> null
            },
        )
    }
    return figures
}

@Composable
internal fun offersFigures(decisions: DecisionEconomics): List<Figure> = listOf(
    Figure(
        stringResource(R.string.analytics_tier1_accept_rate),
        decisions.acceptanceRate?.let { Formats.percent(it) } ?: EMPTY_VALUE,
        if (decisions.received == 0) stringResource(R.string.offers_tab_no_offers_yet)
        else stringResource(
            R.string.offers_tab_offers_count_caption,
            Formats.commaInt(decisions.received), offerNoun(decisions.received),
        ),
    ),
)

@Composable
internal fun timeFigures(netPerHour: NetPerHourPair, gaps: GapStats): List<Figure> = listOf(
    Figure(
        stringResource(R.string.analytics_tier1_net_per_hour_working),
        netPerHour.whileWorking?.let { "${Formats.money(it)}/hr" } ?: EMPTY_VALUE,
        when {
            netPerHour.onlineMillis <= 0L -> stringResource(R.string.time_tab_rate_none)
            netPerHour.workingTimeUnmeasured -> stringResource(R.string.time_tab_rate_working_unmeasured)
            else -> formatDuration(netPerHour.workingMillis)
        },
    ),
    Figure(
        stringResource(R.string.analytics_tier1_typical_gap),
        gaps.medianMillis?.let { formatDuration(it) } ?: EMPTY_VALUE,
        if (!gaps.hasGaps) stringResource(R.string.time_tab_gaps_none)
        else stringResource(
            R.string.time_tab_gaps_coverage_format,
            Formats.commaInt(gaps.count), pluralGap(gaps.count), Formats.commaInt(gaps.completionsConsidered),
        ),
    ),
)
