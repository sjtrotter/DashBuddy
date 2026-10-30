package cloud.trotter.log.gig.feature.bubble.hud.expense

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import cloud.trotter.log.gig.domain.analytics.ExpenseCategory
import cloud.trotter.log.gig.feature.bubble.R

val ExpensePick.labelResource: Int get() = when (this) {
    ExpensePick.Fuel -> R.string.hud_expense_gas
    is ExpensePick.Expense -> when (category) {
        ExpenseCategory.FOOD -> R.string.hud_log_cat_food
        ExpenseCategory.PARKING -> R.string.hud_log_cat_parking
        ExpenseCategory.CAR_CARE -> R.string.hud_log_cat_car_care
        ExpenseCategory.SUPPLIES -> R.string.hud_log_cat_supplies
        ExpenseCategory.OTHER -> R.string.hud_log_cat_other
    }
}
val ExpensePick.tagName: String get() = when (this) {
    ExpensePick.Fuel -> "gas"
    is ExpensePick.Expense -> category.name.lowercase(java.util.Locale.ROOT)
}
internal val ExpensePick.icon: ImageVector get() = when (this) {
    ExpensePick.Fuel -> Icons.Outlined.LocalGasStation
    is ExpensePick.Expense -> when (category) {
        ExpenseCategory.FOOD -> Icons.Outlined.Restaurant
        ExpenseCategory.PARKING -> Icons.Outlined.LocalParking
        ExpenseCategory.CAR_CARE -> Icons.Outlined.Build
        // Inventory2 is the approved placeholder; the developer may swap it.
        ExpenseCategory.SUPPLIES -> Icons.Outlined.Inventory2
        ExpenseCategory.OTHER -> Icons.Outlined.MoreHoriz
    }
}
