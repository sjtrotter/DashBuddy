package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppLegend
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegment
import cloud.trotter.dashbuddy.core.designsystem.component.AppStackBar
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.format.Formats
import kotlin.math.roundToInt

/** Placeholder shown for a figure with no measurable input yet (Money-tab parity). */
private const val EMPTY_VALUE = "—"

/**
 * Decisions tab (#315 H3): the frozen-decision review for the selected period, top→bottom — the
 * offer funnel (accept/decline/timeout + acceptance-rate headline), the value of saying no (Σ est.
 * net of declines), and score-vs-outcome (avg score + avg est. $/hr, accepted vs declined). Pure
 * data in ([DecisionEconomics]), no side effects.
 *
 * **Everything here is a FROZEN decision-time estimate, not realized net** — labelled "est." at
 * every economic surface so the dasher reads them as what the verdict projected, never what was
 * actually earned (an economy edit never re-costs a past offer). Aggregate-only: no per-offer list,
 * no merchant/customer text (Principle 6/7). Every number routes through the [Formats] SSOT.
 */
@Composable
fun DecisionsTab(
    decisions: DecisionEconomics,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OfferFunnelCard(decisions)
        ValueOfSayingNoCard(decisions)
        ScoreVsOutcomeCard(decisions)
    }
}

/** Offer funnel: acceptance-rate headline + a stacked accept/decline/timeout bar with a legend. */
@Composable
private fun OfferFunnelCard(decisions: DecisionEconomics) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "OFFER FUNNEL", style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        if (decisions.received == 0) {
            EmptyRow("No offers in this period yet.")
            return@AppCard
        }

        val rate = decisions.acceptanceRate?.let { "${(it * 100).roundToInt()}%" } ?: EMPTY_VALUE
        Text(text = rate, style = AppTheme.num.heroNum, color = c.text)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "acceptance rate · ${Formats.commaInt(decisions.received)} " +
                if (decisions.received == 1) "offer" else "offers",
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
        Spacer(Modifier.height(14.dp))

        // Counts drive the bar; the legend surfaces the raw count per segment (the rate is the hero).
        val segments = listOf(
            AppSegment("Accepted", decisions.accepted.toFloat(), c.good, note = Formats.commaInt(decisions.accepted)),
            AppSegment("Declined", decisions.declined.toFloat(), c.bad, note = Formats.commaInt(decisions.declined)),
            AppSegment("Timed out", decisions.timedOut.toFloat(), c.neutral, note = Formats.commaInt(decisions.timedOut)),
        )
        AppStackBar(segments, height = 14.dp)
        Spacer(Modifier.height(10.dp))
        AppLegend(segments)
    }
}

/**
 * The "value of saying no": Σ frozen est. net pay of the offers that were declined. It's the money
 * the dasher chose to skip, by the offer's own decision-time estimate — not a realized figure.
 */
@Composable
private fun ValueOfSayingNoCard(decisions: DecisionEconomics) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "VALUE OF SAYING NO", style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        if (decisions.declined == 0) {
            Text(text = EMPTY_VALUE, style = AppTheme.num.heroNum, color = c.text2)
            Spacer(Modifier.height(2.dp))
            EmptyRow("No declines in this period.")
            return@AppCard
        }
        Text(text = "~${Formats.money(decisions.declinedEstNet)}", style = AppTheme.num.heroNum, color = c.text)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "est. net skipped across ${Formats.commaInt(decisions.declined)} declined " +
                if (decisions.declined == 1) "offer" else "offers",
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
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
        Text(text = "SCORE VS OUTCOME", style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        if (decisions.received == 0) {
            EmptyRow("No offers in this period yet.")
            return@AppCard
        }
        // Header
        ComparisonRow(
            label = "",
            score = "Avg score",
            perHour = "Est. \$/hr",
            labelColor = c.text3,
            valueStyleHeader = true,
        )
        Spacer(Modifier.height(8.dp))
        ComparisonRow(
            label = "Accepted",
            score = decisions.avgScoreAccepted?.let { Formats.decimal(it, 1) } ?: EMPTY_VALUE,
            perHour = decisions.avgEstPerHourAccepted?.let { Formats.money(it) } ?: EMPTY_VALUE,
            labelColor = c.good,
        )
        Spacer(Modifier.height(8.dp))
        ComparisonRow(
            label = "Declined",
            score = decisions.avgScoreDeclined?.let { Formats.decimal(it, 1) } ?: EMPTY_VALUE,
            perHour = decisions.avgEstPerHourDeclined?.let { Formats.money(it) } ?: EMPTY_VALUE,
            labelColor = c.bad,
        )
    }
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

@Composable
private fun EmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.text3,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
