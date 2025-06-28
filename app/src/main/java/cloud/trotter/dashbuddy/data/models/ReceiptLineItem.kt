package cloud.trotter.dashbuddy.data.models

// A generic model for any line item in our receipt format.
data class ReceiptLineItem(
    val label: String,
    val amount: String,
    val isSubtotal: Boolean = false
)