package cloud.trotter.dashbuddy.data.models.dash.history

data class DashSummary(
    val dashId: Long,
    val earnings: Double,
    val time: String, // e.g., "1:00 PM - 8:30 PM"
    val stats: String, // e.g., "12 Orders • 44.21 Miles"
    val offers: List<OfferSummary>,
    var isExpanded: Boolean = false
)