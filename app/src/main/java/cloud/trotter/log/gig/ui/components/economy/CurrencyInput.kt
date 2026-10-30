package cloud.trotter.log.gig.ui.components.economy

import kotlinx.coroutines.Deferred
import cloud.trotter.log.gig.ui.main.settings.garage.WriteResult

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import java.util.Locale

internal fun nonnegativeNumber(text: String): Double? =
    text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

@Composable
fun CurrencyInput(
    label: String,
    value: Double,
    onValueChange: NumberSave,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    showCurrencySymbol: Boolean = true,
    enabled: Boolean = true,
    supportingText: String? = null,
    fieldKey: String = label,
    integer: Boolean = false,
    valid: (String) -> Double? = ::nonnegativeNumber,
) {
    val session = LocalEditSession.current
    val formatted = if (showCurrencySymbol) editableAmount(value) else value.toString().removeSuffix(".0")
    val state = session.field(fieldKey, formatted, { valid(it) != null }) { onValueChange(requireNotNull(valid(it))) }
    val finish = session.finisher(fieldKey)
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = state.text,
        onValueChange = { session.edit(fieldKey, it) },
        label = { Text(label) },
        prefix = if (showCurrencySymbol) ({ Text("$") }) else null,
        suffix = suffix?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        isError = state.error,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { finish(); focusManager.clearFocus() }),
        singleLine = true,
        modifier = modifier.onFocusChanged {
            if (state.focused && !it.isFocused) finish()
            session.focus(fieldKey, it.isFocused)
        },
    )
}

@Composable
fun IntegerInput(label: String, value: Int, onValueChange: (Int) -> Deferred<WriteResult>,
    modifier: Modifier = Modifier, enabled: Boolean = true, fieldKey: String = label) =
    CurrencyInput(label, value.toDouble(), { onValueChange(it.toInt()) }, modifier,
        showCurrencySymbol = false, enabled = enabled, fieldKey = fieldKey, integer = true,
        valid = { it.toIntOrNull()?.takeIf { value -> value >= 1 }?.toDouble() })

internal fun editableAmount(value: Double): String =
    if (value == 0.0) "0" else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

@Composable
fun DecimalInput(label: String, value: Double, onValueChange: NumberSave,
    enabled: Boolean = true, supportingText: String? = null, fieldKey: String = label) =
    CurrencyInput(label, value, onValueChange, showCurrencySymbol = false,
        enabled = enabled, supportingText = supportingText, fieldKey = fieldKey)
