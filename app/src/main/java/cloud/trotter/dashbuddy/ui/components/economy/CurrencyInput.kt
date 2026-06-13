package cloud.trotter.dashbuddy.ui.components.economy

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import java.util.Locale

/**
 * A currency-style input field. Renders the current value as a $-prefixed
 * decimal; the user can scrub it via a numeric keyboard. Commits every
 * parseable keystroke (live footers update as you type).
 *
 * While the field is FOCUSED the text is the source of truth — the upstream
 * value round-trip must not re-seed it mid-typing (typing "1." used to commit
 * 1.0, round-trip, and reset the text to "1", eating the decimal separator —
 * #350). On blur, the formatted upstream value re-syncs/normalizes the text.
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
    var isFocused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(editableAmount(value)) }
    LaunchedEffect(value, isFocused) {
        if (!isFocused) text = editableAmount(value)
    }

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
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
    )
}

/**
 * A non-currency decimal input field. Same shape (and the same focus-aware
 * text handling, #350) as [CurrencyInput] but no `$` prefix. Use this for any
 * value that isn't money (e.g. minutes, miles).
 */

/**
 * An integer-style input field with a custom label. No prefix. Same
 * focus-aware text handling as [CurrencyInput] (#350).
 * Used for: phone plan line count, etc.
 */
@Composable
fun IntegerInput(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value, isFocused) {
        if (!isFocused) text = value.toString()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
    )
}

// Input-field machine string (#467): the editable amount the field round-trips
// via toDoubleOrNull — Locale.US, trailing zeros trimmed ("5.5", "0"), NOT the
// display money SSOT (Formats.money, which renders "$5.50"). Renamed from
// formatCurrency to avoid confusion with the removed display formatter.
private fun editableAmount(value: Double): String =
    if (value == 0.0) "0" else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
