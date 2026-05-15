package cloud.trotter.dashbuddy.ui.components.economy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * A two-field row that captures a cost + interval pair (e.g. "$60 oil change
 * every 5000 miles"). The app converts the pair to a per-mile value
 * internally; the user only enters dollars + miles they already know.
 *
 * Used for: tires, oil changes, brakes, fluids, misc repairs.
 */
@Composable
fun PairedCurrencyAndIntervalInput(
    costLabel: String,
    costValue: Double,
    onCostChange: (Double) -> Unit,
    intervalLabel: String,
    intervalValue: Double,
    onIntervalChange: (Double) -> Unit,
    intervalSuffix: String = "mi",
    modifier: Modifier = Modifier,
    helperText: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CurrencyInput(
                label = costLabel,
                value = costValue,
                onValueChange = onCostChange,
                modifier = Modifier.weight(1f),
            )
            IntervalInput(
                label = intervalLabel,
                value = intervalValue,
                onValueChange = onIntervalChange,
                suffix = intervalSuffix,
                modifier = Modifier.weight(1f),
            )
        }
        if (!helperText.isNullOrBlank()) {
            Text(
                text = helperText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * An interval input — a numeric text field with a unit suffix (default "mi").
 */
@Composable
private fun IntervalInput(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    suffix: String,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(formatInterval(value)) }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toDoubleOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier,
    )
}

private fun formatInterval(value: Double): String = when {
    value == 0.0 -> "0"
    value == value.toLong().toDouble() -> value.toLong().toString()
    else -> String.format(Locale.US, "%.0f", value)
}
