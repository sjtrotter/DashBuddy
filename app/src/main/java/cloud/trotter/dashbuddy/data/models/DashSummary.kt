package cloud.trotter.dashbuddy.data.models

// The top-level summary for a single dash.
data class DashSummary(
    val dashId: Long,
    val startTime: Long,
    val endTime: Long?,
    val totalEarned: Double,
    val deliveryCount: Int = 0,
    val totalMiles: Double = 0.0,
    // This now holds the fully prepared display data.
    val offerDisplays: List<OfferDisplay> = emptyList(),
    var isExpanded: Boolean = false
)