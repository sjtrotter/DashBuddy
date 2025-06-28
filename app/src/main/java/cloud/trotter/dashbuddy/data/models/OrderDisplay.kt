package cloud.trotter.dashbuddy.data.models

// Represents a single order for display, including its tip breakdown.
data class OrderDisplay(
    val storeName: String,
    val status: String,
    val tipLines: List<ReceiptLineItem>
)