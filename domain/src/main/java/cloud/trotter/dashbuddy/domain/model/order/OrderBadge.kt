package cloud.trotter.dashbuddy.domain.model.order

/**
 * Represents various textual badges that apply to a specific order/leg within an offer,
 * such as equipment requirements or specific handling notes for that part of the delivery.
 *
 * Recognition is data, not code (CLAUDE.md): the JSON rule engine mints these badges as parse
 * output (e.g. `ParsedFieldsFactory` maps `isRedCard`/`isLargeOrder`/`hasAlcohol` to constants),
 * so the enum is a stable set of serialized keys (persisted by name via `DataTypeConverters`). The
 * old Kotlin text-matching machinery (`findAllBadgesInOrderBlock`/`findBadgesInText` and the
 * `badgeText` column) was superseded by the rules and removed.
 *
 * Each constant carries a human-readable [displayName] — the ONE label owner for the badge
 * (#942), mirroring [cloud.trotter.dashbuddy.domain.model.offer.OfferBadge]. The bubble's badge
 * pill row used to keep its own per-badge label map, which drifted and silently missed branches.
 */
enum class OrderBadge(
    val displayName: String,
) {
    /** Indicates that a Red Card is required for this specific order (e.g., for payment at the store). */
    RED_CARD(displayName = "Red Card"),

    /** Indicates this specific order is a large catering order, often requiring more space or different handling. */
    LARGE_ORDER(displayName = "Large Order"),

    /** Indicates a pizza bag is required for this specific order. */
    PIZZA_BAG(displayName = "Pizza Bag"),

    /** Indicates this specific order contains alcohol and may require ID check at pickup or dropoff for this leg. */
    ALCOHOL(displayName = "Alcohol"),
}
