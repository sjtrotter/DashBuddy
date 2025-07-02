package cloud.trotter.dashbuddy.data.models.dash.history

data class OfferSummary(
    val offerId: Long,
    val summaryLine: String, // e.g., "Offered $15.50 for Panera Bread: Accepted"
    val doordashPayLines: List<ReceiptLine>,
    val orders: List<OrderSummary>,
    val totalLine: ReceiptLine,
    var isExpanded: Boolean = false
)