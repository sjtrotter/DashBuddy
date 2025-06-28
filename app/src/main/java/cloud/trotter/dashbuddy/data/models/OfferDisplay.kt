package cloud.trotter.dashbuddy.data.models

data class OfferDisplay(
    // Unexpanded
    val summaryText: String,
    val status: String,
    val totalAmount: String,
    val totalMiles: String,
    val offerBadges: Set<Int> = emptySet(),

    // Expanded
    val payLines: List<ReceiptLineItem> = emptyList(),
    val orders: List<OrderDisplay> = emptyList(),
    val actualStats: ActualStats?,
    var isExpanded: Boolean = false
)