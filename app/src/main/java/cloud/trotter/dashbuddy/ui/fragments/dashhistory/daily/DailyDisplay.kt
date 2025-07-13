package cloud.trotter.dashbuddy.ui.fragments.dashhistory.daily

import cloud.trotter.dashbuddy.data.offer.OfferStatus
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.SummaryStats
import java.time.LocalDate

/**
 * Represents the complete, ready-to-display data for a single Daily History page.
 * This is the top-level object the UI will observe.
 */
data class DailyDisplay(
    val date: LocalDate,
    val stats: SummaryStats, // For the sticky summary card at the top
    val dashSummaries: List<DashSummary> // For the main RecyclerView of dash sessions
) {
    /**
     * Represents the data for a single, expandable dash session card.
     * Corresponds to the `item_daily_dash_summary.xml` layout.
     */
    data class DashSummary(
        val dashId: Long,
        val startTime: String,      // Formatted e.g., "11:05 AM"
        val stopTime: String,       // Formatted e.g., "2:45 PM"
        val zoneName: String,
        val totalEarnings: Double,
        val offerSummaries: List<OfferSummary> // For the nested offers RecyclerView
    )

    /**
     * Represents the data for a single, expandable offer row within a dash.
     * Corresponds to the `item_daily_offer_summary.xml` layout.
     */
    data class OfferSummary(
        val offerId: Long,
        val summaryLine: String,    // e.g., "The Corner Bistro: 2.1 mi"
        val actualPay: Double,      // The final, calculated pay (AppPay + Tips)
        val status: OfferStatus,    // To know if it was ACCEPTED or DECLINED
        val payBreakdown: List<ReceiptLine> // For the innermost pay breakdown RecyclerView
    )

    /**
     * Represents a single line item in the pay breakdown receipt.
     * Corresponds to the `item_dash_summary_receipt_line.xml` layout.
     */
    data class ReceiptLine(
        val label: String,          // e.g., "Base Pay", "Customer Tip"
        val amount: String          // Formatted currency, e.g., "$8.50"
    )

    companion object {
        /**
         * A factory function to create a default/empty state for a given date.
         * This is useful for initializing the UI before real data has loaded.
         */
        fun empty(date: LocalDate): DailyDisplay {
            return DailyDisplay(
                date = date,
                stats = SummaryStats(),
                dashSummaries = emptyList()
            )
        }
    }
}