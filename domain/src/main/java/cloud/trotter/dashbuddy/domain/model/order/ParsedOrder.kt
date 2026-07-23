package cloud.trotter.dashbuddy.domain.model.order

import kotlinx.serialization.Serializable

@Serializable

data class ParsedOrder(
    /** The index of this order in the offer. */
    val orderIndex: Int,
    /** The type of order. */
    val orderType: OrderType,
    /** The name of the store for this order. */
    val storeName: String,
    /** The number of items in this order. */
    val itemCount: Int,
    /** Indicates if the item count is estimated. */
    val isItemCountEstimated: Boolean,
    /** The badge(s) associated with this order. */
    val badges: Set<OrderBadge>,
    /**
     * Which label [itemCount] was denominated by on the offer card (#823 Phase 1). [CountUnit.UNITS]
     * only when the parsed count is a units-only figure (`(64 units)`); [CountUnit.ITEMS] for an
     * items figure, an estimated/absent count, or a non-shop order. Default [CountUnit.ITEMS] so
     * every existing construction and snapshot decodes unchanged (additive, `@Serializable` default);
     * kept last so positional constructions stay valid.
     */
    val countUnit: CountUnit = CountUnit.ITEMS,
)