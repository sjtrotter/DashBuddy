package cloud.trotter.dashbuddy.data.models

data class OrderDisplay(
    val summaryText: String, // e.g., "Store 1 - x.x mi (Completed)"
    val tipLines: List<ReceiptLineItem>,
    val orderBadges: Set<Int> = emptySet()
)