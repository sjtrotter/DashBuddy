package cloud.trotter.dashbuddy.data.models

// Represents a single offer for display, including its pay breakdown and orders.
data class OfferDisplay(
    val summaryText: String,
    val status: String,
    val payLines: List<ReceiptLineItem>,
    val orders: List<OrderDisplay>,
    val total: String,
    var isExpanded: Boolean = false
)