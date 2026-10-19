package cloud.trotter.dashbuddy.feature.bubble.hud.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.bubble.R
import cloud.trotter.dashbuddy.feature.bubble.hud.keypad.Keypad
import java.util.Locale

@Composable
fun ColumnScope.ExpenseSheet(
    model: ExpenseSheetModel,
    onBack: () -> Unit,
    onField: (ExpenseField) -> Unit,
    onLabel: (String) -> Unit,
    onSelectLabel: (String) -> Unit,
    onNewLabel: () -> Unit,
    onKeyboardDone: () -> Unit,
    onDigit: (Int) -> Unit,
    onPoint: () -> Unit,
    onBackspace: () -> Unit,
    onLog: () -> Unit,
) {
    val title = stringResource(model.pick.labelResource)
    Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.hud_odometer_back))
                    }
                    Icon(model.pick.icon, null, Modifier.size(22.dp), tint = AppTheme.colors.text2)
                    Text(title, color = AppTheme.colors.text, style = MaterialTheme.typography.titleSmall)
                }
                if (model.pick.hasLabel) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        model.recentLabels.forEachIndexed { index, label ->
                            AppChip(label, uppercase = false,
                                container = if (model.label == label) AppTheme.colors.accent else AppTheme.colors.surface3,
                                color = if (model.label == label) AppTheme.colors.accentText else AppTheme.colors.text2,
                                modifier = Modifier.testTag("expense.label.$index")
                                    .semantics { selected = model.label == label }
                                    .clickable(enabled = !model.busy, role = Role.Button) { onSelectLabel(label) })
                        }
                        AppChip(stringResource(R.string.hud_expense_new_label), uppercase = false,
                            modifier = Modifier.testTag("expense.label.new")
                                .clickable(enabled = !model.busy, role = Role.Button, onClick = onNewLabel))
                    }
                    if (model.keyboardOpen) {
                        val focus = remember { FocusRequester() }
                        val keyboard = LocalSoftwareKeyboardController.current
                        LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
                        OutlinedTextField(model.label, onLabel, singleLine = true, enabled = !model.busy,
                            modifier = Modifier.fillMaxWidth().testTag("expense.label.input").focusRequester(focus),
                            placeholder = { Text(stringResource(R.string.hud_expense_label)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onKeyboardDone() }))
                    } else if (model.label.isNotEmpty() && model.label !in model.recentLabels) {
                        AppChip(model.label, uppercase = false, container = AppTheme.colors.accent,
                            color = AppTheme.colors.accentText,
                            modifier = Modifier.clickable(enabled = !model.busy, onClick = onNewLabel))
                    }
                }
                AmountField(model.totalText.ifEmpty {
                    if (model.totalCents == 0L) EMPTY_VALUE else Formats.money(model.totalCents / 100.0).removePrefix("$")
                }, "$", true, model.activeField == ExpenseField.TOTAL, !model.busy,
                    "expense.total", stringResource(R.string.hud_expense_total)) { onField(ExpenseField.TOTAL) }
                if (model.pick == ExpensePick.Fuel) AmountField(model.gallonsText.ifEmpty { EMPTY_VALUE }, "gal", false,
                    model.activeField == ExpenseField.GALLONS, !model.busy, "expense.gallons",
                    stringResource(R.string.hud_expense_gallons)) { onField(ExpenseField.GALLONS) }
            }
            Keypad(onDigit = { if (!model.busy) onDigit(it) },
                onPoint = { if (!model.busy) onPoint() }, onBackspace = { if (!model.busy) onBackspace() })
        }
        Button(onClick = onLog, enabled = model.canLog, modifier = Modifier.fillMaxWidth().testTag("expense.log")) {
            Text(stringResource(R.string.hud_expense_log, Formats.money(model.totalCents / 100.0), title.lowercase(Locale.getDefault())))
        }
        if (model.failure == ExpenseFailure.WRITE) Text(stringResource(R.string.hud_expense_failed),
            color = AppTheme.colors.warn, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AmountField(text: String, unit: String, leading: Boolean, active: Boolean, enabled: Boolean,
    tag: String, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).testTag(tag)
        .background(AppTheme.colors.surface3, shape)
        .then(if (active) Modifier.border(1.dp, AppTheme.colors.accent, shape) else Modifier)
        .semantics { selected = active; contentDescription = description }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (leading) Text(unit, color = AppTheme.colors.text3)
        Text(text, Modifier.weight(1f), textAlign = TextAlign.End, style = AppTheme.num.mdNum, color = AppTheme.colors.text)
        if (!leading) Text(unit, Modifier.padding(start = 4.dp), color = AppTheme.colors.text3)
    }
}
