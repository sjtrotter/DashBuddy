package cloud.trotter.dashbuddy.data.models.dash.history

import java.util.Date

data class DaySummary(
    val date: Date,
    val totalEarnings: Double,
    val totalTimeInMillis: Long,
    val totalMiles: Double,
    val dashCount: Int,
    val orderCount: Int,
    val dashes: List<DashSummary>,
    var isExpanded: Boolean = false
)