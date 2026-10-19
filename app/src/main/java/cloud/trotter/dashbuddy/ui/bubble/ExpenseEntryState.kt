package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.feature.bubble.hud.expense.*
import cloud.trotter.dashbuddy.feature.bubble.hud.keypad.*

@Stable
internal class ExpenseEntryState {
    var open by mutableStateOf(false)
    var page by mutableStateOf(ExpensePage.SUBCARD)
    var pick by mutableStateOf<ExpensePick>(ExpensePick.Fuel)
        private set
    var activeField by mutableStateOf(ExpenseField.TOTAL)
    var totalDigits by mutableStateOf("")
        private set
    var gallonsDigits by mutableStateOf("")
        private set
    private var labelValue by mutableStateOf("")
    var label: String
        get() = labelValue
        set(value) {
            if (value.codePointCount(0, value.length) <= ExpenseLabel.MAX_CODE_POINTS) labelValue = value
        }
    var keyboardOpen by mutableStateOf(false)
    var entryId by mutableStateOf(0L)
        private set
    val totalCents get() = MoneyEntry(totalDigits).cents

    fun newLabel(recentLabels: List<String>) {
        if (label in recentLabels || label.isEmpty()) label = ""
        editLabel()
    }
    fun editLabel() { keyboardOpen = true }

    fun show() {
        entryId++
        page = ExpensePage.SUBCARD
        keyboardOpen = false
        open = true
    }
    fun select(pick: ExpensePick) {
        entryId++
        this.pick = pick
        activeField = ExpenseField.TOTAL
        totalDigits = ""; gallonsDigits = ""; label = ""; keyboardOpen = false
        page = ExpensePage.SHEET
    }
    fun digit(n: Int) {
        when (activeField) {
            ExpenseField.TOTAL -> totalDigits = MoneyEntry(totalDigits).digit(n).digits
            ExpenseField.GALLONS -> gallonsDigits = DecimalEntry(gallonsDigits).digit(n).digits
        }
    }
    fun point() {
        when (activeField) {
            ExpenseField.TOTAL -> totalDigits = MoneyEntry(totalDigits).point().digits
            ExpenseField.GALLONS -> gallonsDigits = DecimalEntry(gallonsDigits).point().digits
        }
    }
    fun backspace() {
        when (activeField) {
            ExpenseField.TOTAL -> totalDigits = MoneyEntry(totalDigits).backspace().digits
            ExpenseField.GALLONS -> gallonsDigits = DecimalEntry(gallonsDigits).backspace().digits
        }
    }
    fun back() {
        keyboardOpen = false
        if (page == ExpensePage.SHEET) page = ExpensePage.SUBCARD else open = false
    }

    companion object {
        val Saver = listSaver<ExpenseEntryState, Any>(
            save = { listOf(it.open, it.page.name, when (val pick = it.pick) {
                ExpensePick.Fuel -> "FUEL"
                is ExpensePick.Expense -> pick.category.name
            }, it.activeField.name, it.totalDigits, it.gallonsDigits, it.label, it.keyboardOpen, it.entryId) },
            restore = { values -> ExpenseEntryState().apply {
                open = values[0] as Boolean
                page = ExpensePage.valueOf(values[1] as String)
                pick = if (values[2] == "FUEL") ExpensePick.Fuel
                    else ExpensePick.Expense(ExpenseCategory.fromStorage(values[2] as String))
                activeField = ExpenseField.valueOf(values[3] as String)
                totalDigits = values[4] as String; gallonsDigits = values[5] as String
                label = values[6] as String; keyboardOpen = values[7] as Boolean; entryId = values[8] as Long
            } },
        )
    }
}

@Composable
internal fun rememberExpenseEntryState() = rememberSaveable(saver = ExpenseEntryState.Saver) { ExpenseEntryState() }
