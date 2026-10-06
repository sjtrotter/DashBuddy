package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.ui.components.DisclosureRow
import cloud.trotter.dashbuddy.core.designsystem.component.AppLegend
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegment
import cloud.trotter.dashbuddy.core.designsystem.component.AppStackBar
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.PayMix
import cloud.trotter.dashbuddy.domain.format.Formats

/**
 * "What you earned" (#973 / #1024 B1 / #1135 PR 1) — Earned decomposed EXACTLY into base pay / tips (cash
 * labelled) / recorded-not-itemized / reported-not-matched, as one bar with a real-dollar legend plus the
 * tips insight. Lives inside [MoneyWentCard] as the "what came in" half (a SECTION, not a card — #1024 B1).
 *
 * Honesty behaviours, all from [PayMix] (§9 — thin data is stated, never smoothed over): zero itemization
 * still renders the bar (the not-itemized segment IS the recorded pay, named as such — no "100 % bonuses"
 * path exists any more); partial itemization states its coverage with the stacked-order explanation behind
 * a disclosure; a recorded-above-reported amount, a negative itemization and an unreconciled identity are
 * each stated outright in the bad tone. Percentages are allowed HERE and only here (the tips insight is a
 * share statement).
 */
@Composable
internal fun PayMixSection(mix: PayMix, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    var partialCoverageExpanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.money_tab_pay_mix_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))

        val segments = payMixSegments(mix)
        AppStackBar(segments, height = 14.dp)
        Spacer(Modifier.height(10.dp))
        AppLegend(segments)

        Spacer(Modifier.height(10.dp))
        Text(
            text = insightLine(mix),
            style = MaterialTheme.typography.bodyMedium,
            color = c.text2,
        )

        if (!mix.breakdownComplete) {
            Spacer(Modifier.height(6.dp))
            // COLLAPSE (#1024 B1, kept): the "N of M" coverage marker stays visible; the stacked-order
            // explanation behind it sits behind the shared DisclosureRow affordance (§9 — the caveat
            // itself is never dropped, only its explanation).
            DisclosureRow(
                text = stringResource(
                    R.string.money_tab_pay_mix_partial_coverage_format,
                    Formats.commaInt(mix.deliveriesWithBreakdown),
                    Formats.commaInt(mix.deliveries),
                ),
                expanded = partialCoverageExpanded,
                onToggle = { partialCoverageExpanded = !partialCoverageExpanded },
            )
            if (partialCoverageExpanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.money_tab_pay_mix_partial_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text3,
                )
            }
        }
        // Each gap line renders only when there is a gap to name (§9: state what the rows prove, never a $0 line).
        if (mix.notItemized > 0.0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (mix.estimatedFromOffers > 0.0) stringResource(
                    R.string.money_tab_pay_mix_not_itemized_estimate_format,
                    Formats.money(mix.notItemized), Formats.money(mix.estimatedFromOffers),
                ) else stringResource(R.string.money_tab_pay_mix_not_itemized_format, Formats.money(mix.notItemized)),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
        }
        if (mix.notMatched > 0.0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.money_tab_pay_mix_not_matched_caption),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
        }
        if (mix.recordedAboveReported > 0.0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.money_tab_over_attributed_callout_format, Formats.money(mix.recordedAboveReported)),
                style = MaterialTheme.typography.bodySmall,
                color = c.bad,
            )
        }
        if (mix.notItemizedNegative) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.money_tab_pay_mix_negative_itemization_format,
                    Formats.money(-mix.notItemized),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = c.bad,
            )
        }
        if (mix.unreconciled) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.money_tab_pay_mix_unreconciled),
                style = MaterialTheme.typography.bodySmall,
                color = c.bad,
            )
        }
    }
}

/** Positive bar geometry; signed amounts and deductions are stated in the legend and captions. */
@Composable
private fun payMixSegments(mix: PayMix): List<AppSegment> {
    val c = AppTheme.colors
    val tipsNote = if (mix.cashTips > 0.0) {
        stringResource(
            R.string.money_tab_pay_mix_tips_with_cash_format,
            Formats.money(mix.tipsTotal),
            Formats.money(mix.cashTips),
        )
    } else {
        Formats.money(mix.tipsTotal)
    }
    return listOf(
        AppSegment(
            label = stringResource(R.string.money_tab_pay_mix_segment_base),
            value = mix.basePay.toFloat().coerceAtLeast(0f),
            color = c.accent,
            note = Formats.money(mix.basePay),
        ),
        AppSegment(
            label = stringResource(R.string.money_tab_pay_mix_segment_tips),
            value = mix.tipsTotal.toFloat().coerceAtLeast(0f),
            color = c.good,
            note = tipsNote,
        ),
        AppSegment(
            label = stringResource(R.string.money_tab_pay_mix_segment_not_itemized),
            value = mix.notItemized.toFloat().coerceAtLeast(0f),
            color = c.neutral,
            note = Formats.money(mix.notItemized),
        ),
        AppSegment(
            label = stringResource(R.string.money_tab_pay_mix_segment_not_matched),
            value = mix.notMatched.toFloat().coerceAtLeast(0f),
            color = c.warn,
            note = Formats.money(mix.notMatched),
        ),
    )
}

/**
 * "tips were 57% of what you earned" — or "tips were at least 57% of what you earned" when part of the window went
 * un-itemized, because the un-itemized rows may hold tips too and the measured share is then a floor,
 * not the value.
 */
@Composable
private fun insightLine(mix: PayMix): String {
    val share = mix.tipShare ?: return stringResource(R.string.money_tab_pay_mix_no_insight)
    return if (mix.breakdownComplete) {
        stringResource(R.string.money_tab_pay_mix_insight_format, Formats.percent(share))
    } else {
        stringResource(R.string.money_tab_pay_mix_insight_floor_format, Formats.percent(share))
    }
}
