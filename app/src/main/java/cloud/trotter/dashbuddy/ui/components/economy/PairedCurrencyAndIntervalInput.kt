package cloud.trotter.dashbuddy.ui.components.economy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Either field finishing commits only the valid halves whose text was edited. */
@Composable
fun PairedCurrencyAndIntervalInput(
    costLabel: String,
    costValue: Double,
    intervalLabel: String,
    intervalValue: Double,
    onValueChange: (Double?, Double?) -> Unit,
    intervalSuffix: String = "mi",
    modifier: Modifier = Modifier,
    helperText: String? = null,
    enabled: Boolean = true,
) {
    val cost = remember { FinishedNumberState(editableAmount(costValue), ::nonnegativeNumber) }
    val interval = remember { FinishedNumberState(formatInterval(intervalValue), ::nonnegativeNumber) }
    SideEffect { cost.sync(editableAmount(costValue)) }
    SideEffect { interval.sync(formatInterval(intervalValue)) }
    val finish = {
        val c = if (cost.dirty) cost.valid(cost.text) else null
        val i = if (interval.dirty) interval.valid(interval.text) else null
        if (c != null || i != null) {
            if (c != null) cost.markCommitted()
            if (i != null) interval.markCommitted()
            onValueChange(c, i)
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FinishedNumberField(costLabel, cost, finish, Modifier.weight(1f), currency = true, enabled = enabled)
            FinishedNumberField(intervalLabel, interval, finish, Modifier.weight(1f), suffix = intervalSuffix, enabled = enabled)
        }
        if (!helperText.isNullOrBlank()) Text(helperText, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun formatInterval(value: Double): String = when {
    value == 0.0 -> "0"
    value == value.toLong().toDouble() -> value.toLong().toString()
    else -> String.format(Locale.US, "%.0f", value)
}
