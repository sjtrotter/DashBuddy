package cloud.trotter.dashbuddy.feature.bubble.hud.expense

import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.feature.bubble.hud.keypad.DecimalEntry

sealed interface ExpensePick {
    data object Fuel : ExpensePick
    data class Expense(val category: ExpenseCategory) : ExpensePick
}
enum class ExpenseField { TOTAL, GALLONS }
enum class ExpenseFailure { NONE, WRITE }
enum class ExpensePage { SUBCARD, SHEET }

val ExpensePick.hasLabel: Boolean get() = when (this) {
    ExpensePick.Fuel -> false
    is ExpensePick.Expense -> when (category) {
        ExpenseCategory.FOOD, ExpenseCategory.PARKING -> false
        ExpenseCategory.CAR_CARE, ExpenseCategory.SUPPLIES, ExpenseCategory.OTHER -> true
    }
}

data class ExpenseSheetModel(
    val pick: ExpensePick,
    val totalCents: Long = 0,
    val gallonsText: String = "",
    val label: String = "",
    val recentLabels: List<String> = emptyList(),
    val activeField: ExpenseField = ExpenseField.TOTAL,
    val busy: Boolean = false,
    val failure: ExpenseFailure = ExpenseFailure.NONE,
    val keyboardOpen: Boolean = false,
    val totalText: String = "",
) {
    val canLog: Boolean get() = totalCents > 0 && !busy &&
        (pick != ExpensePick.Fuel || DecimalEntry(gallonsText).positive)
}
