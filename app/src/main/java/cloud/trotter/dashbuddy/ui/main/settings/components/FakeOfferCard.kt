package cloud.trotter.dashbuddy.ui.main.settings.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.ui.formatters.recommendationLabel

@Composable
fun FakeOfferCard(
    evaluation: OfferEvaluation,
    modifier: Modifier = Modifier
) {
    val c = AppTheme.colors

    val targetContainerColor = when (evaluation.action) {
        OfferAction.ACCEPT -> c.goodBg
        OfferAction.DECLINE -> c.badBg
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val targetContentColor = c.text

    val borderColor = when (evaluation.action) {
        OfferAction.ACCEPT -> c.good
        OfferAction.DECLINE -> c.bad
        else -> c.neutral
    }

    val animatedContainerColor by animateColorAsState(targetContainerColor, label = "container")
    val animatedContentColor by animateColorAsState(targetContentColor, label = "content")

    val hasFuelCost = evaluation.fuelCostEstimate > 0.0
    val hasNonFuelCost = evaluation.nonFuelCostEstimate > 0.0
    val hasAnyCost = hasFuelCost || hasNonFuelCost

    Column(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, borderColor, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(
                containerColor = animatedContainerColor,
                contentColor = animatedContentColor
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // --- Recommendation + Score ---
                Text(
                    text = stringResource(
                        R.string.fake_offer_score_format,
                        evaluation.action.recommendationLabel(),
                        evaluation.score.toInt(),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = borderColor
                )

                Spacer(modifier = Modifier.height(12.dp))

                // --- Pay line ---
                if (hasAnyCost) {
                    val payLineFormat = if (evaluation.isUsingDefaults)
                        R.string.fake_offer_pay_line_estimate_format
                    else
                        R.string.fake_offer_pay_line_format
                    Text(
                        text = stringResource(
                            payLineFormat,
                            Formats.money(evaluation.payAmount),
                            Formats.money(evaluation.netPayAmount),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = animatedContentColor
                    )
                    if (hasFuelCost) {
                        Text(
                            text = stringResource(R.string.fake_offer_fuel_cost_format, Formats.money(evaluation.fuelCostEstimate)),
                            style = MaterialTheme.typography.bodySmall,
                            color = animatedContentColor.copy(alpha = 0.65f)
                        )
                    }
                    if (hasNonFuelCost) {
                        Text(
                            text = stringResource(R.string.fake_offer_nonfuel_cost_format, Formats.money(evaluation.nonFuelCostEstimate)),
                            style = MaterialTheme.typography.bodySmall,
                            color = animatedContentColor.copy(alpha = 0.65f)
                        )
                    }
                } else {
                    Text(
                        text = Formats.money(evaluation.payAmount),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = animatedContentColor
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = animatedContentColor.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(12.dp))

                // --- Metric row: $/mi | $/hr | items ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MetricCell(
                        label = stringResource(R.string.fake_offer_metric_dollar_per_mile_label),
                        value = Formats.money(evaluation.dollarsPerMile),
                        color = animatedContentColor
                    )
                    MetricCell(
                        label = stringResource(R.string.fake_offer_metric_dollar_per_hour_label),
                        value = Formats.money(evaluation.dollarsPerHour),
                        color = animatedContentColor
                    )
                    MetricCell(
                        label = stringResource(R.string.fake_offer_metric_items_label),
                        value = evaluation.itemCount.toInt().toString(),
                        color = animatedContentColor
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // --- Detail row: distance | time ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MetricCell(
                        label = stringResource(R.string.fake_offer_metric_miles_label),
                        value = Formats.decimal(evaluation.distanceMiles),
                        color = animatedContentColor
                    )
                    MetricCell(
                        label = stringResource(R.string.fake_offer_metric_est_time_label),
                        value = stringResource(R.string.fake_offer_metric_est_time_value_format, evaluation.estimatedTimeMinutes.toInt()),
                        color = animatedContentColor
                    )
                }
            }
        }

        // --- Warnings (#80) ---
        if (evaluation.warnings.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            evaluation.warnings.forEach { warning ->
                Text(
                    text = stringResource(R.string.fake_offer_warning_format, warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.65f)
        )
    }
}