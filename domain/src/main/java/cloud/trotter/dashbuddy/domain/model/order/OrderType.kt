package cloud.trotter.dashbuddy.domain.model.order

/**
 * The kind of order within an offer, as far as it affects downstream logic.
 *
 * Recognition is data, not code (CLAUDE.md): a platform's JSON ruleset emits the order type as a
 * parse-output string, which [ParsedOrder] carries as this enum. `ParsedFieldsFactory` resolves the
 * string via the intrinsic [valueOf]. The only behavioural distinction the state/economics layers
 * draw is [isShoppingOrder] (a shop leg costs shopping time); everything else is a display concern
 * owned by the UI. The old Kotlin text-matching helpers
 * (`fromTypeName`/`orderTypeCount`/`allTypeNames`/`typeName`) were superseded by the rules and
 * removed — a new platform adds vocabulary through its ruleset + corpus, not here.
 */
enum class OrderType(
    val isShoppingOrder: Boolean,
) {
    /** A standard pickup of pre-prepared items from a store — the neutral default for a bare order. */
    PICKUP(isShoppingOrder = false),

    /** The order requires the Dasher to shop for the items at the store. */
    SHOP_FOR_ITEMS(isShoppingOrder = true),

    /**
     * A parse produced an order-type string no [OrderType] constant maps to — a gap between the
     * ruleset vocabulary and this enum. Non-shopping so it degrades to the safe default in
     * economics; the mismatch is logged at the parse site (`ParsedFieldsFactory`).
     */
    UNKNOWN(isShoppingOrder = false),
}
