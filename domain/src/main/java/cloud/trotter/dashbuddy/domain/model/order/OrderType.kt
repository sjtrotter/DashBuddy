package cloud.trotter.dashbuddy.domain.model.order

/**
 * Represents the various types of orders that can be offered by DoorDash.
 * Each type has a display name that matches the text on the offer screen
 * and a flag indicating if it's a shopping-type order.
 *
 * Recognition is data, not code (CLAUDE.md): the JSON rule engine emits the order type as parse
 * output, which `ParsedFieldsFactory` maps to a constant via the intrinsic [valueOf]. The old
 * Kotlin text-matching helpers (`fromTypeName`/`orderTypeCount`/`allTypeNames`) were superseded by
 * the rules and removed.
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
}
