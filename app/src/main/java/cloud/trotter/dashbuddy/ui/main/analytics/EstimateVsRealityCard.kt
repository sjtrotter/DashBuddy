package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppBar
import cloud.trotter.dashbuddy.core.designsystem.component.AppBarChart
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.EstimateVsReality
import cloud.trotter.dashbuddy.domain.format.Formats

/**
 * "Estimate vs reality" (#975 / brief §7.5): two bars — the frozen est. $/hr the verdict projected
 * when each accepted offer arrived, against the realized net $/hr those jobs actually produced —
 * plus the plain-English ratio between them.
 *
 * **The two bars are deliberately labelled differently.** Everywhere else on this tab a number is a
 * frozen estimate; the right bar is not, and the whole point of the card collapses if the driver
 * reads them as the same kind of figure (§9 — realized net is a separate, differently-labeled
 * number).
 *
 * **The population is stated, not implied.** [EstimateVsReality] excludes an offer with no frozen
 * estimate (#936 fail-closed evaluation), one that was never linked to a job, one sharing its job
 * with another accepted offer (a stack settles on receipts that can't be split per offer), and one
 * whose job recorded no net or no time. The caption always names how many of the window's accepted
 * offers actually made it in, and a sample under
 * [MIN_CONFIDENT_OFFERS][EstimateVsReality.Companion.MIN_CONFIDENT_OFFERS] is called thin rather
 * than presented as settled.
 *
 * Pure data in, no lambdas out — nothing here is interactive.
 */
@Composable
fun EstimateVsRealityCard(comparison: EstimateVsReality, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.offers_tab_est_vs_reality_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))

        val est = comparison.estPerHour
        val realized = comparison.realizedPerHour
        if (!comparison.hasComparison || est == null || realized == null) {
            EmptyRow(stringResource(R.string.offers_tab_est_vs_reality_none))
            return@AppCard
        }

        // The two figures ride ABOVE the bars: a bar states a proportion, the text states the value —
        // the driver must never have to read a dollar amount off a bar height.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PerHourValue(est, Modifier.weight(1f))
            PerHourValue(realized, Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        AppBarChart(
            bars = listOf(
                AppBar(stringResource(R.string.offers_tab_est_vs_reality_bar_est), est.toFloat().coerceAtLeast(0f)),
                AppBar(
                    label = stringResource(R.string.offers_tab_est_vs_reality_bar_realized),
                    value = realized.toFloat().coerceAtLeast(0f),
                    highlight = true,
                ),
            ),
            height = 72.dp,
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = comparison.ratio
                ?.let { stringResource(R.string.offers_tab_est_vs_reality_ratio_format, Formats.percent(it)) }
                ?: stringResource(R.string.offers_tab_est_vs_reality_no_ratio),
            style = MaterialTheme.typography.bodyMedium,
            color = c.text2,
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                R.string.offers_tab_est_vs_reality_population_format,
                Formats.commaInt(comparison.offers),
                Formats.commaInt(comparison.acceptedOffers),
                offerNoun(comparison.acceptedOffers),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )

        if (comparison.isThinData) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.offers_tab_est_vs_reality_thin_format,
                    Formats.commaInt(comparison.offers),
                    offerNoun(comparison.offers),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = c.warn,
            )
        }
    }
}

/** One "$X/hr" figure over its bar. */
@Composable
private fun PerHourValue(perHour: Double, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.offers_tab_est_vs_reality_per_hour_format, Formats.money(perHour)),
        style = AppTheme.num.smNum,
        color = AppTheme.colors.text,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}
