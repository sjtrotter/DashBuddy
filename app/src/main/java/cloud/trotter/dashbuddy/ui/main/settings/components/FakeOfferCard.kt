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
import androidx.compose.ui.text.font.FontWeight
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import java.util.Locale
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
                    text = "${evaluation.action.recommendationLabel()}  ·  ${evaluation.score.toInt()}pts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = borderColor
                )

                Spacer(modifier = Modifier.height(12.dp))

                // --- Pay line ---
                if (hasAnyCost) {
                    val netSuffix = if (evaluation.isUsingDefaults) " (est.)" else ""
                    Text(
                        text = "$${fmt(evaluation.payAmount)} gross  →  $${fmt(evaluation.netPayAmount)} net$netSuffix",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = animatedContentColor
                    )
                    if (hasFuelCost) {
                        Text(
                            text = "−$${fmt(evaluation.fuelCostEstimate)} fuel",
                            style = MaterialTheme.typography.bodySmall,
                            color = animatedContentColor.copy(alpha = 0.65f)
                        )
                    }
                    if (hasNonFuelCost) {
                        Text(
                            text = "−$${fmt(evaluation.nonFuelCostEstimate)} wear & fixed costs",
                            style = MaterialTheme.typography.bodySmall,
                            color = animatedContentColor.copy(alpha = 0.65f)
                        )
                    }
                } else {
                    Text(
                        text = "$${fmt(evaluation.payAmount)}",
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
                        label = "\$/mi",
                        value = "$${fmt(evaluation.dollarsPerMile)}",
                        color = animatedContentColor
                    )
                    MetricCell(
                        label = "\$/hr",
                        value = "$${fmt(evaluation.dollarsPerHour)}",
                        color = animatedContentColor
                    )
                    MetricCell(
                        label = "items",
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
                        label = "miles",
                        value = fmt(evaluation.distanceMiles),
                        color = animatedContentColor
                    )
                    MetricCell(
                        label = "est. time",
                        value = "~${evaluation.estimatedTimeMinutes.toInt()} min",
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
                    text = "\u26a0\ufe0f $warning",
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

private fun fmt(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)