package cloud.trotter.dashbuddy.ui.components.economy

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import java.util.Locale

/**
 * A currency-style input field. Renders the current value as a $-prefixed
 * decimal; the user can scrub it via a numeric keyboard. Commits on blur or
 * enter — there's no separate "submit" button.
 *
 * Used for: purchase price, insurance/registration deltas, phone plan total.
 */
@Composable
fun CurrencyInput(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
) {
    var text by remember(value) { mutableStateOf(formatCurrency(value)) }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toDoubleOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        prefix = { Text("$") },
        suffix = suffix?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier,
    )
}

/**
 * A non-currency decimal input field. Same shape as [CurrencyInput] but no
 * `$` prefix. Use this for any value that isn't money (e.g. minutes, miles).
 */
@Composable
fun NumberInput(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
) {
    var text by remember(value) { mutableStateOf(formatCurrency(value)) }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toDoubleOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier,
    )
}

/**
 * An integer-style input field with a custom label. No prefix.
 * Used for: phone plan line count, etc.
 */
@Composable
fun IntegerInput(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}

private fun formatCurrency(value: Double): String =
    if (value == 0.0) "0" else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
