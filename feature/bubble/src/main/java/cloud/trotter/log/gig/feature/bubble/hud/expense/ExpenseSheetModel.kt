package cloud.trotter.log.gig.feature.bubble.hud.expense

import cloud.trotter.log.gig.domain.analytics.ExpenseCategory
import cloud.trotter.log.gig.feature.bubble.hud.keypad.MoneyEntry
import cloud.trotter.log.gig.feature.bubble.hud.keypad.DecimalEntry

object ExpenseLabel {
    const val MAX_CODE_POINTS = 40
}

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
    val totalText: String = "",
    val gallonsText: String = "",
    val label: String = "",
    val recentLabels: List<String> = emptyList(),
    val activeField: ExpenseField = ExpenseField.TOTAL,
    val busy: Boolean = false,
    val failure: ExpenseFailure = ExpenseFailure.NONE,
    val keyboardOpen: Boolean = false,
) {
    val totalCents: Long get() = MoneyEntry(totalText).cents
    val canLog: Boolean get() = totalCents > 0 && !busy &&
        (pick != ExpensePick.Fuel || DecimalEntry(gallonsText).positive)
}
