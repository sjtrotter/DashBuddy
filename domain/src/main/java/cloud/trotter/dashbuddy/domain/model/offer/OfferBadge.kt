package cloud.trotter.dashbuddy.domain.model.offer

/**
 * Represents various textual badges or indicators that can appear on a DoorDash offer screen.
 *
 * Recognition is data, not code (CLAUDE.md): the JSON rule engine emits these badges as parse
 * output, so the enum is a stable set of serialized/UI keys (persisted by name via
 * `DataTypeConverters`). Each constant carries only a human-readable [displayName]; the old
 * Kotlin text-matching machinery (`findAllBadgesInScreen`/`findBadgesInText` and the
 * `exactMatchText`/`containsText`/`regexPattern` columns) was superseded by the rules and removed.
 */
enum class OfferBadge(
    val displayName: String,
) {
    /** Indicates the offer is marked as high paying by DoorDash. */
    HIGH_PAYING(displayName = "High Paying"),

    /** Indicates priority access due to Dasher status (Platinum, Gold, Silver) or Pro Shopper ratings. */
    PRIORITY_ACCESS(displayName = "Priority Access"),

    /** Indicates all orders in a stacked offer are from a single store. */
    ALL_ORDERS_SAME_STORE(displayName = "All Orders Same Store"),

    /** Indicates both orders in a stacked offer are for the same customer. */
    BOTH_ORDERS_SAME_CUSTOMER(displayName = "Both Orders Same Customer"),

    /** Indicates customer can add items to a shopping order before checkout. */
    ITEMS_CAN_BE_ADDED(displayName = "Items Can Be Added"),

    /** Indicates a Sharpie is recommended, often for Shop & Deliver orders. */
    SHARPIE_RECOMMENDED(displayName = "Sharpie Recommended"),

    /** Indicates the order contains age-restricted or other restricted items. */
    CONTAINS_RESTRICTED_ITEMS(displayName = "Contains Restricted Items"),

    /** Indicates the order requires the Dasher to be 18+. */
    AGE_RESTRICTED_18_PLUS(displayName = "Age Restricted 18+"),

    /** Indicates the order requires the Dasher to be 21+ (often for alcohol). */
    AGE_RESTRICTED_21_PLUS(displayName = "Age Restricted 21+"),

    /** Indicates the Dasher must check the recipient's ID. */
    CHECK_RECIPIENT_ID(displayName = "Check Recipient ID"),

    /** Indicates the order includes alcohol (often part of 'Contains restricted items'). */
    INCLUDES_ALCOHOL(displayName = "Includes Alcohol"),

    /** Indicates a cash on delivery (COD) order. */
    COLLECT_CASH(displayName = "Collect Cash"),

    /** Indicates the order might involve processing returns (often for alcohol or retail). */
    MAY_NEED_RETURNS(displayName = "May Need Returns"),
}
