package cloud.trotter.dashbuddy.ui.components.economy

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

/** Local text survives stored-value round trips while the field is focused. */
internal class FinishedNumberState(initial: String, val valid: (String) -> Double?) {
    var text by mutableStateOf(initial)
    var focused by mutableStateOf(false)
    var dirty by mutableStateOf(false)
    private var storedText = initial
    fun edit(input: String) {
        if (input != text) { text = input; dirty = true }
    }
    fun focus() {
        if (!focused) dirty = false
        focused = true
    }
    /** Only text the dasher has typed and not yet committed is protected; otherwise the field shows the store. */
    fun sync(stored: String) {
        storedText = stored
        if (!focused || !dirty) { text = stored; dirty = false }
    }
    fun blur() {
        focused = false
        text = storedText
        dirty = false
    }
    fun markCommitted() { dirty = false }
    fun commit(onCommit: (Double) -> Unit) {
        if (!dirty) return
        val value = valid(text) ?: return
        markCommitted()
        onCommit(value)
    }
}

internal fun nonnegativeNumber(text: String): Double? =
    text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

@Composable
internal fun FinishedNumberField(
    label: String,
    state: FinishedNumberState,
    finish: () -> Unit,
    modifier: Modifier = Modifier,
    currency: Boolean = false,
    suffix: String? = null,
    enabled: Boolean = true,
    integer: Boolean = false,
    supportingText: String? = null,
) {
    val latestFinish by rememberUpdatedState(finish)
    val focusManager = LocalFocusManager.current
    DisposableEffect(state) { onDispose { latestFinish() } }
    OutlinedTextField(
        value = state.text,
        onValueChange = state::edit,
        label = { Text(label) },
        prefix = if (currency) ({ Text("$") }) else null,
        suffix = suffix?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        isError = state.valid(state.text) == null,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { latestFinish(); focusManager.clearFocus() }),
        singleLine = true,
        modifier = modifier.onFocusChanged {
            if (state.focused && !it.isFocused) latestFinish()
            if (it.isFocused) state.focus() else state.blur()
        },
    )
}

/** Currency commits once on blur, Done or disposal; invalid text stays local. */
@Composable
fun CurrencyInput(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    showCurrencySymbol: Boolean = true,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    val formatted = if (showCurrencySymbol) editableAmount(value) else value.toString().removeSuffix(".0")
    val state = remember { FinishedNumberState(formatted, ::nonnegativeNumber) }
    SideEffect { state.sync(formatted) }
    FinishedNumberField(label, state, { state.commit(onValueChange) }, modifier,
        currency = showCurrencySymbol, suffix = suffix, enabled = enabled, supportingText = supportingText)
}

@Composable
fun IntegerInput(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val state = remember { FinishedNumberState(value.toString()) { text ->
        text.toIntOrNull()?.takeIf { it >= 0 }?.toDouble()
    } }
    SideEffect { state.sync(value.toString()) }
    FinishedNumberField(label, state, { state.commit { onValueChange(it.toInt()) } }, modifier,
        enabled = enabled, integer = true)
}

// Editable machine string, distinct from display money formatting.
internal fun editableAmount(value: Double): String =
    if (value == 0.0) "0" else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

@Composable
fun DecimalInput(label: String, value: Double, onValueChange: (Double) -> Unit,
    enabled: Boolean = true, supportingText: String? = null) =
    CurrencyInput(label, value, onValueChange, showCurrencySymbol = false,
        enabled = enabled, supportingText = supportingText)
