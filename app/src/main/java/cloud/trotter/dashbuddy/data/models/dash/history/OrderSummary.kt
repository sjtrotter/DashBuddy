package cloud.trotter.dashbuddy.data.models.dash.history

data class OrderSummary(
    val orderId: Long,
    val summaryLine: String, // e.g., "Order at Panera Bread: Completed"
    val tipLines: List<ReceiptLine>
)