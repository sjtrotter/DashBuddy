package cloud.trotter.dashbuddy.data.stats

// 1. Just the info needed to calculate Dash Hours & Miles
data class DashStatsTuple(
    val id: Long,
    val startTime: Long,
    val stopTime: Long?,
    val totalDistance: Double?
)

// 2. Just the info needed for Active Hours & Earnings grouping
data class OfferStatsTuple(
    val id: Long,
    val dashId: Long,
    val acceptTime: Long?,
    val status: String,
    val timestamp: Long // needed for Monthly/Daily grouping
)

// 3. Just the info needed for Active Hours & Mileage
data class OrderStatsTuple(
    val id: Long,
    val offerId: Long,
    val completionTimestamp: Long?,
    val mileage: Double?
)

// 4. Just the Money. We use this for BOTH AppPay and Tips.
data class PayStatsTuple(
    val offerId: Long, // We will map 'orderId' to this field for tips
    val amount: Double
)