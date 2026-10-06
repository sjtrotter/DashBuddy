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
import cloud.trotter.dashbuddy.core.designsystem.component.AppLegend
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegment
import cloud.trotter.dashbuddy.core.designsystem.component.AppStackBar
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.PayMix
import cloud.trotter.dashbuddy.domain.format.Formats

/**
 * More-mode Earned composition, inside [MoneyWentCard]. The bar and legend keep the recorded
 * pay breakdown, cash and offer-estimated subset. Negative itemization stays visible here;
 * the Tips tile owns share/coverage and Needs-a-look owns unmatched and over-attributed amounts.
 */
@Composable
internal fun PayMixSection(mix: PayMix, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.money_tab_pay_mix_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))

        // An empty window draws no bar: AppStackBar gives every zero segment the same minimum weight, which
        // would fabricate a four-way composition out of nothing (Astra r1 P3).
        if (mix.gross <= UNATTRIBUTED_EPSILON && mix.recorded <= UNATTRIBUTED_EPSILON) {
            Text(
                text = stringResource(R.string.money_tab_pay_mix_no_insight),
                style = MaterialTheme.typography.bodyMedium,
                color = c.text3,
            )
            return@Column
        }
        val segments = payMixSegments(mix)
        AppStackBar(segments, height = 14.dp)
        Spacer(Modifier.height(10.dp))
        AppLegend(segments)

        if (mix.estimatedFromOffers > UNATTRIBUTED_EPSILON) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.money_tab_pay_mix_estimated_format, Formats.money(mix.estimatedFromOffers)),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
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
    }
}

/** Positive bar geometry; signed amounts and deductions are stated in the legend and captions. */
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
