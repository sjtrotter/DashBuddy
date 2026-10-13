package cloud.trotter.dashbuddy.domain.analytics

/** #1333: user-logged expense kinds; the HUD's chips. Stored by name. */
enum class ExpenseCategory {
    FOOD, PARKING, SUPPLIES, OTHER;

    companion object {
        fun fromStorage(name: String?): ExpenseCategory = entries.firstOrNull { it.name == name } ?: OTHER
    }
}
