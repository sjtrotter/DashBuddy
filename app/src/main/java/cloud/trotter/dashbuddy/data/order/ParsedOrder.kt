package cloud.trotter.dashbuddy.data.order

data class ParsedOrder(
    /** The index of this order in the offer. */
    val orderIndex: Int,
    /** The type of order. */
    val orderType: String,
    /** The name of the store for this order. */
    val storeName: String,
    /** The number of items in this order. */
    val itemCount: Int,
    /** Indicates if the item count is estimated. */
    val isItemCountEstimated: Boolean,
    /** The badge(s) associated with this order. */
    val badges: Set<OrderBadge>,
) {
    fun toOrderEntity(offerId: Long): OrderEntity {
        return OrderEntity(
            orderIndex = orderIndex,
            orderType = orderType,
            storeName = storeName,
            itemCount = itemCount,
            isItemCountEstimated = isItemCountEstimated,
            offerId = offerId,
        )
    }
}
