package cloud.trotter.dashbuddy.ui.components.economy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Layout only: each input submits exactly its own value. */
@Composable
fun PairedCurrencyAndIntervalInput(
    costLabel: String,
    costValue: Double,
    intervalLabel: String,
    intervalValue: Double,
    onCostChange: NumberSave,
    onIntervalChange: NumberSave,
    costKey: String,
    intervalKey: String,
    intervalSuffix: String = "mi",
    modifier: Modifier = Modifier,
    helperText: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CurrencyInput(costLabel, costValue, onCostChange, Modifier.weight(1f), enabled = enabled, fieldKey = costKey)
            CurrencyInput(intervalLabel, intervalValue, onIntervalChange, Modifier.weight(1f),
                suffix = intervalSuffix, showCurrencySymbol = false, enabled = enabled, fieldKey = intervalKey)
        }
        if (!helperText.isNullOrBlank()) Text(helperText, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
