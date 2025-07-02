package cloud.trotter.dashbuddy.data.models.dash.history

data class ReceiptLine(
    val label: String,
    val amount: String // Formatted as "$5.00"
)