package cloud.trotter.dashbuddy.data.models

import cloud.trotter.dashbuddy.data.offer.OfferStatus

sealed interface HistoryListItem {
    val id: String
}

data class MonthHeaderItem(
    val monthYear: String,
    val timestamp: Long
) : HistoryListItem {
    override val id: String = monthYear
}

data class OfferItem(
    val offerId: Long,
    val dashId: Long,
    val status: OfferStatus,
    val summaryText: String,
    val payAndMiles: String,
    val aggregatedBadges: Set<Int>
) : HistoryListItem {
    override val id: String = "offer-$offerId"
}

data class DashItem(
    val dashId: Long,
    val dayId: Long,
    val timeRange: String,
    val duration: String,
    val isExpanded: Boolean = false,
    val offers: List<OfferItem> // Offers are now nested inside the Dash
) : HistoryListItem {
    override val id: String = "dash-$dashId"
}

data class DaySummaryItem(
    val dateTimestamp: Long,
    val dayOfMonth: String,
    val dayOfWeek: String,
    val totalEarnings: String,
    val statsLine1: String, // FIX: Changed from statsLine
    val statsLine2: String, // FIX: Added second line
    val isExpanded: Boolean = true,
    val dashes: List<DashItem>
) : HistoryListItem {
    override val id: String = "day-$dateTimestamp"
}