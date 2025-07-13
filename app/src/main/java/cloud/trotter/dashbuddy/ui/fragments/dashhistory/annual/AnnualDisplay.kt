package cloud.trotter.dashbuddy.ui.fragments.dashhistory.annual

import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.SummaryStats
import java.text.DateFormatSymbols

/**
 * Represents the complete, ready-to-display data for a single Annual History page.
 */
data class AnnualDisplay(
    val year: Int,
    val stats: SummaryStats,
    val monthSummaries: List<MonthInYearSummary>
) {
    /**
     * Represents the data for one of the 12 month cards in the annual grid.
     */
    data class MonthInYearSummary(
        val month: Int, // 1 for January, 12 for December
        val totalEarnings: Double,
        val hasData: Boolean // True if there were any dashes this month
    ) {
        val monthNameAbbreviation: String
            get() = DateFormatSymbols.getInstance().shortMonths[month - 1].uppercase()
    }

    companion object {
        /**
         * A factory function to create a default/empty state for a given year.
         * This is useful for initializing the UI before data has loaded.
         */
        fun empty(year: Int): AnnualDisplay {
            // Create a list of 12 empty month summaries, one for each month.
            val emptyMonths = (1..12).map { month ->
                MonthInYearSummary(
                    month = month,
                    totalEarnings = 0.0,
                    hasData = false
                )
            }
            return AnnualDisplay(
                year = year,
                stats = SummaryStats(),
                monthSummaries = emptyMonths
            )
        }
    }
}