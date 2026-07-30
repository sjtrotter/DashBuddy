package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppLegend
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegment
import cloud.trotter.dashbuddy.core.designsystem.component.AppStackBar
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.analytics.EstimateVsReality
import cloud.trotter.dashbuddy.domain.analytics.OfferFilter
import cloud.trotter.dashbuddy.domain.format.Formats

/**
 * Offers tab (#975 / brief §5 — the tab formerly called *Decisions*): the frozen-decision review for
 * the selected window, top→bottom —
 *
 *  1. the **top pair** (brief §5): the acceptance funnel AND "said no to ~$X" side by side, so the
 *     top of the screen carries two ideas instead of one;
 *  2. **estimate vs reality** (§7.5): frozen est. $/hr at decision time against realized net $/hr for
 *     the accepted offers whose jobs actually finished;
 *  3. **score vs outcome**: the avg score / avg est. $/hr comparison this tab already had;
 *  4. **the offers list** (§7.4): the window's offers, filterable, paged, newest first.
 *
 * Pure data in, lambdas out — no side effects, no repository reads (Principle 1/3).
 *
 * **Everything frozen is labelled "est."** and the standing disclosure (§9) is now VISIBLE on the
 * tab, not just documented here: these are what the verdict projected when the offer arrived, never
 * realized net. The one exception is §7.5's realized bar, which is deliberately labelled
 * *differently* precisely because it IS a realized figure.
 *
 * Privacy: merchant names are driver-owned display data; `offer_records` carries no customer fields
 * at all, so this surface cannot leak one (Principle 6). Every number routes through the [Formats]
 * SSOT.
 */
@Composable
fun OffersTab(
    decisions: DecisionEconomics,
    estimateVsReality: EstimateVsReality,
    offersFeed: OffersFeedState,
    onSelectFilter: (OfferFilter) -> Unit,
    onShowAllOffers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OfferTopPairCard(decisions)
        EstimateVsRealityCard(estimateVsReality)
        ScoreVsOutcomeCard(decisions)
        OffersListCard(
            feed = offersFeed,
            onSelectFilter = onSelectFilter,
            onShowAll = onShowAllOffers,
        )
        FrozenEstimateDisclosure()
    }
}

/**
 * The top pair (brief §5): acceptance rate and "said no to ~$X" as two headline halves of ONE card,
 * with the accept/decline/timeout bar + legend spanning the full width beneath them.
 *
 * One card rather than two side-by-side cards on purpose: the stacked bar and its legend belong to
 * the funnel half but need the whole width to stay readable on a phone, and splitting them into a
 * half-width card would have cost the per-outcome counts — the legend IS where the raw numbers live.
 *
 * **The declined figure states its population** (#975): `estNetPay` is null on a #936 no-verdict
 * offer, so Σ silently under-counts. When no decline was priced at all the card says so instead of
 * rendering "$0.00", which would read as "you skipped nothing of value" (§9).
 */
@Composable
private fun OfferTopPairCard(decisions: DecisionEconomics) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.offers_tab_offer_funnel_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))

        if (decisions.received == 0) {
            EmptyRow(stringResource(R.string.offers_tab_no_offers_yet))
            return@AppCard
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AcceptanceHalf(decisions, Modifier.weight(1f))
            SaidNoHalf(decisions, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))

        // Counts drive the bar; the legend surfaces the raw count per segment (the rate is the hero).
        val segments = listOf(
            AppSegment(stringResource(R.string.offers_tab_segment_accepted), decisions.accepted.toFloat(), c.good, note = Formats.commaInt(decisions.accepted)),
            AppSegment(stringResource(R.string.offers_tab_segment_declined), decisions.declined.toFloat(), c.bad, note = Formats.commaInt(decisions.declined)),
            AppSegment(stringResource(R.string.offers_tab_segment_timed_out), decisions.timedOut.toFloat(), c.neutral, note = Formats.commaInt(decisions.timedOut)),
        )
        AppStackBar(segments, height = 14.dp)
        Spacer(Modifier.height(10.dp))
        AppLegend(segments)
    }
}

/** Idea 1 of the pair: the acceptance rate over the window's closing offers. */
@Composable
private fun AcceptanceHalf(decisions: DecisionEconomics, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(modifier) {
        Text(
            text = decisions.acceptanceRate?.let { Formats.percent(it) } ?: EMPTY_VALUE,
            style = AppTheme.num.heroNum,
            color = c.text,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.offers_tab_acceptance_rate_caption),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
        Text(
            text = stringResource(
                R.string.offers_tab_offers_count_caption,
                Formats.commaInt(decisions.received),
                offerNoun(decisions.received),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
    }
}

/**
 * Idea 2 of the pair: the est. net the driver chose to skip — by the offers' OWN decision-time
 * estimates, over the declines that carried one.
 */
@Composable
private fun SaidNoHalf(decisions: DecisionEconomics, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(modifier) {
        Text(
            text = stringResource(R.string.offers_tab_said_no_title),
            style = MaterialTheme.typography.labelSmall,
            color = c.text3,
        )
        Spacer(Modifier.height(2.dp))
        when {
            decisions.declined == 0 -> {
                Text(text = EMPTY_VALUE, style = AppTheme.num.heroNum, color = c.text2)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.offers_tab_no_declines_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text3,
                )
            }

            // Every decline went unpriced (#936 no-verdict): a "$0.00" here would be a claim about
            // the money rather than about the data, so state the gap instead (§9).
            !decisions.hasDeclinedEstimates -> {
                Text(text = EMPTY_VALUE, style = AppTheme.num.heroNum, color = c.text2)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.offers_tab_said_no_no_estimates_format,
                        Formats.commaInt(decisions.declined),
                        declineNoun(decisions.declined),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text3,
                )
            }

            else -> {
                Text(
                    text = stringResource(
                        R.string.offers_tab_est_net_skipped_format,
                        Formats.money(decisions.declinedEstNet),
                    ),
                    style = AppTheme.num.heroNum,
                    color = c.text,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.offers_tab_said_no_population_format,
                        Formats.commaInt(decisions.declinedWithEstimate),
                        Formats.commaInt(decisions.declined),
                        declineNoun(decisions.declined),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text3,
                )
            }
        }
    }
}

/**
 * Score vs outcome — a 2-row comparison of the avg frozen score + avg est. $/hr for accepted vs
 * declined offers: the "is my judgment matching the verdicts" read. Both economic columns are
 * decision-time estimates (labelled "est.").
 */
@Composable
private fun ScoreVsOutcomeCard(decisions: DecisionEconomics) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.offers_tab_score_vs_outcome_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))
        if (decisions.received == 0) {
            EmptyRow(stringResource(R.string.offers_tab_no_offers_yet))
            return@AppCard
        }
        // Header
        ComparisonRow(
            label = "",
            score = stringResource(R.string.offers_tab_avg_score_header),
            perHour = stringResource(R.string.offers_tab_est_per_hour_header),
            labelColor = c.text3,
            valueStyleHeader = true,
        )
        Spacer(Modifier.height(8.dp))
        ComparisonRow(
            label = stringResource(R.string.offers_tab_segment_accepted),
            score = decisions.avgScoreAccepted?.let { Formats.decimal(it, 1) } ?: EMPTY_VALUE,
            perHour = decisions.avgEstPerHourAccepted?.let { Formats.money(it) } ?: EMPTY_VALUE,
            labelColor = c.good,
        )
        Spacer(Modifier.height(8.dp))
        ComparisonRow(
            label = stringResource(R.string.offers_tab_segment_declined),
            score = decisions.avgScoreDeclined?.let { Formats.decimal(it, 1) } ?: EMPTY_VALUE,
            perHour = decisions.avgEstPerHourDeclined?.let { Formats.money(it) } ?: EMPTY_VALUE,
            labelColor = c.bad,
        )
    }
}

/**
 * The standing disclosure (brief §5 + §9), now on screen rather than only in a KDoc: everything
 * above except the realized bar is a frozen decision-time estimate.
 */
@Composable
private fun FrozenEstimateDisclosure() {
    Text(
        text = stringResource(R.string.offers_tab_frozen_disclosure),
        style = MaterialTheme.typography.bodySmall,
        color = AppTheme.colors.text3,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ComparisonRow(
    label: String,
    score: String,
    perHour: String,
    labelColor: Color,
    valueStyleHeader: Boolean = false,
) {
    val c = AppTheme.colors
    val valueStyle = if (valueStyleHeader) MaterialTheme.typography.labelSmall else AppTheme.num.smNum
    val valueColor = if (valueStyleHeader) c.text3 else c.text
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = labelColor,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = score,
            style = valueStyle,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = perHour,
            style = valueStyle,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.width(96.dp),
        )
    }
}

/** "offer" / "offers" — the one owner of the tab's count noun. */
@Composable
internal fun offerNoun(count: Int): String = stringResource(
    if (count == 1) R.string.offers_tab_offer_singular else R.string.offers_tab_offer_plural,
)

/** "decline" / "declines" — the one owner of the tab's decline noun. */
@Composable
internal fun declineNoun(count: Int): String = stringResource(
    if (count == 1) R.string.offers_tab_decline_singular else R.string.offers_tab_decline_plural,
)
