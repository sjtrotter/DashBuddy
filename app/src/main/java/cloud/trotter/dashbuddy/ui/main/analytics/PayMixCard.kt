package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppLegend
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegment
import cloud.trotter.dashbuddy.core.designsystem.component.AppStackBar
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.PayMix
import cloud.trotter.dashbuddy.domain.format.Formats

/**
 * "What made up the gross" (#973 / brief §4.2) — base pay / tips / bonuses & other as one 3-segment
 * bar with a real-dollar legend, plus the one-line insight.
 *
 * **Percentages are allowed HERE and only here** (brief §4.1 vs §4.2): the cost headline must never
 * restate money as a share, but the tips insight *is* a share statement — that's what makes it an
 * insight rather than a fourth dollar figure.
 *
 * Three honesty behaviours, all from [PayMix] (§9 — thin data is stated, never smoothed over):
 *  - **No itemization at all** → the card says the breakdown wasn't recorded instead of drawing a bar
 *    that claims 100% "bonuses". With zero coverage the residue IS the whole gross, and rendering it
 *    as a bonus figure would be a statement about the data dressed up as a fact about the pay.
 *  - **Partial itemization** (the normal case — `basePay`/`tip` are stamped only on a job's SOLE drop,
 *    so every stacked job goes un-itemized) → the bar renders, with a caption naming the coverage and
 *    the insight downgraded to "at least N%".
 *  - **Parts exceeding gross** → stated outright, never absorbed silently by the floor.
 *
 * Cash tips stay additive and labeled: they ride the tips segment's geometry but the legend note names
 * them separately, so driver-attested money is never silently blended into a platform-reported number.
 */
@Composable
fun PayMixCard(mix: PayMix, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    var partialCoverageExpanded by remember { mutableStateOf(false) }
    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.money_tab_pay_mix_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))

        if (!mix.hasBreakdown) {
            Text(
                text = stringResource(R.string.money_tab_pay_mix_not_recorded),
                style = MaterialTheme.typography.bodyMedium,
                color = c.text3,
            )
            return@AppCard
        }

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
            // COLLAPSE: the "N of M" coverage marker stays visible; the stacked-order explanation
            // behind it moves behind the shared DisclosureRow affordance (§9 — the caveat itself is
            // never dropped, only its explanation).
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
        if (mix.partsExceedGross) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.money_tab_pay_mix_exceeds_gross_format,
                    Formats.money(mix.grossOverflow),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = c.warn,
            )
        }
    }
}

/** base pay / tips (incl. any cash, labeled) / bonuses & other — real dollars in the legend. */
@Composable
private fun payMixSegments(mix: PayMix): List<AppSegment> {
    val c = AppTheme.colors
    val tipsNote = if (mix.cashTips > UNATTRIBUTED_EPSILON) {
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
            label = stringResource(R.string.money_tab_pay_mix_segment_bonuses),
            value = mix.bonusesOther.toFloat().coerceAtLeast(0f),
            color = c.neutral,
            note = Formats.money(mix.bonusesOther),
        ),
    )
}

/**
 * "tips were 57% of your pay" — or "tips were at least 57% of your pay" when part of the window went
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

/**
 * A tappable one-line disclosure with a caret — the shared shape behind §4.1's car-cost line. Not a
 * full [cloud.trotter.dashbuddy.core.designsystem.component.AppAccordion]: that component owns a card
 * surface of its own, and this sits INSIDE a card as a footnote, not as a sibling panel.
 */
@Composable
internal fun DisclosureRow(text: String, expanded: Boolean, onToggle: () -> Unit) {
    val c = AppTheme.colors
    val clickLabel = stringResource(
        if (expanded) R.string.money_tab_disclosure_collapse else R.string.money_tab_disclosure_expand,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = clickLabel, role = Role.Button) { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (expanded) "⌃" else "⌄",
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
    }
}
