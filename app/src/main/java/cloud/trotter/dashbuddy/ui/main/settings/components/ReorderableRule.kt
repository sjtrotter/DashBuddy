package cloud.trotter.dashbuddy.ui.main.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.model.config.MetricType
import cloud.trotter.dashbuddy.model.config.ScoringRule
import java.util.Locale

@Composable
fun DraggableRuleRow(
    rule: ScoringRule,
    // FIX 1: Rename this parameter to be clear it's for the handle
    modifier: Modifier = Modifier,
    onUpdate: (ScoringRule) -> Unit
) {
    Card(
        modifier = Modifier // This is the CARD modifier (for layout)
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.isEnabled) MaterialTheme.colorScheme.surfaceContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // FIX 2: Apply the drag logic ONLY to this Icon
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "Reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = modifier // <--- The magic happens here
                    .size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Content (Sliders & Text) - Safe to touch/scroll now!
            Column(modifier = Modifier.weight(1f)) {
                when (rule) {
                    is ScoringRule.MetricRule -> MetricContent(rule, onUpdate)
                    is ScoringRule.MerchantRule -> Text("Merchant Rule (Coming Soon)")
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Checkbox(
                checked = rule.isEnabled,
                onCheckedChange = { isChecked ->
                    val newRule = when (rule) {
                        is ScoringRule.MetricRule -> rule.copy(isEnabled = isChecked)
                        is ScoringRule.MerchantRule -> rule.copy(isEnabled = isChecked)
                    }
                    onUpdate(newRule)
                }
            )
        }
    }
}

@Composable
fun MetricContent(
    rule: ScoringRule.MetricRule,
    onUpdate: (ScoringRule) -> Unit
) {
    val type = rule.metricType
    val value = rule.targetValue

    // Header Row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = type.label,
            style = MaterialTheme.typography.titleMedium,
            color = if (rule.isEnabled) MaterialTheme.colorScheme.onSurface else Color.Gray
        )

        // Value Display (e.g., "$15.00" or "10 mi")
        if (rule.isEnabled) {
            val formatted = when (type) {
                MetricType.PAYOUT, MetricType.ACTIVE_HOURLY -> "$${
                    String.format(
                        Locale.US,
                        "%.2f",
                        value
                    )
                }"

                MetricType.DOLLAR_PER_MILE -> "$${String.format(Locale.US, "%.2f", value)}/mi"
                MetricType.MAX_DISTANCE -> "${String.format(Locale.US, "%.1f", value)} mi"
                MetricType.ITEM_COUNT -> "${value.toInt()} items"
            }
            Text(
                formatted,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }

    // Slider Row
    if (rule.isEnabled) {
        val range = when (type) {
            MetricType.PAYOUT -> 2f..30f
            MetricType.DOLLAR_PER_MILE -> 0.5f..5f
            MetricType.ACTIVE_HOURLY -> 10f..50f
            MetricType.MAX_DISTANCE -> 1f..30f
            MetricType.ITEM_COUNT -> 1f..100f
        }

        Slider(
            value = value,
            onValueChange = { newValue ->
                onUpdate(rule.copy(targetValue = newValue))
            },
            valueRange = range,
            modifier = Modifier.height(24.dp) // Compact slider
        )
    }
}