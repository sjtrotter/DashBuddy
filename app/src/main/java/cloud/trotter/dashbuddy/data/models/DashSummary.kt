package cloud.trotter.dashbuddy.data.models

import cloud.trotter.dashbuddy.data.order.OrderEntity

// A simple data class to hold the summary info for the main list.
data class DashSummary(
    val dashId: Long,
    val startTime: Long,
    val endTime: Long?,
    val totalEarned: Double,
    // These will be calculated and added in the ViewModel
    var deliveryCount: Int = 0,
    var totalMiles: Double = 0.0,
    // UI state properties
    var isExpanded: Boolean = false,
    var orders: List<OrderEntity>? = null // Null until loaded
)