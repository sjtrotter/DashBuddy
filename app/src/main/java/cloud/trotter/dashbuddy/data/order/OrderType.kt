package cloud.trotter.dashbuddy.data.order

/**
 * Represents the various types of orders that can be offered by DoorDash.
 * Each type has a display name that matches the text on the offer screen
 * and a flag indicating if it's a shopping-type order.
 */
enum class OrderType(
    val typeName: String,
    val isShoppingOrder: Boolean
) {
    /**
     *  Standard order pickup from an establishment where items are pre-prepared.
     *  This can include non-restaurant retail if not explicitly 'Retail pickup'.
     */
    PICKUP(
        typeName = "Pickup",
        isShoppingOrder = false
    ),

    /** Specific order pickup from a restaurant, explicitly labeled as such. */
    RESTAURANT_PICKUP(
        typeName = "Restaurant Pickup",
        isShoppingOrder = false
    ),

    /** Order requires the Dasher to shop for the items at the store. */
    SHOP_FOR_ITEMS(
        typeName = "Shop for items",
        isShoppingOrder = true
    ),

    /**
     *  Order pickup from a retail store, typically pre-packaged
     *  or ready for collection, explicitly labeled as such.
     */
    RETAIL_PICKUP(
        typeName = "Retail pickup",
        isShoppingOrder = false
    );
    // Add more types here if new, distinct strings appear on offer screens in the future.

    companion object {
        private val allTypeNames: List<String> by lazy { entries.map { it.typeName } }

        /**
         * Finds an OrderType by its typeName, ignoring case.
         *
         * @param text The typeName string to match.
         * @return The matching OrderType, or null if not found.
         */
        fun fromTypeName(text: String): OrderType? {
            return entries.find { it.typeName.equals(text, ignoreCase = true) }
        }

        /**
         * Counts how many strings in the given list exactly match any of the OrderType typeNames.
         * The match is case-insensitive.
         *
         * @param screenTexts The list of strings to check.
         * @return The total count of matching order type strings.
         */
        fun orderTypeCount(screenTexts: List<String>): Int {
            return screenTexts.count { textToCompare ->
                allTypeNames.any { knownTypeName ->
                    knownTypeName.equals(textToCompare, ignoreCase = true)
                }
            }
        }
    }
}
