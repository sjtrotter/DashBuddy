//package cloud.trotter.dashbuddy.ui.bubble.fragments.dashhistory.monthly
//
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.SummaryStats
//import java.time.LocalDate
//import java.time.format.TextStyle
//import java.util.Locale
//
///**
// * Represents the complete, ready-to-display data for a single Monthly History page.
// * It contains the stats for the summary card and the detailed breakdown for the calendar grid.
// */
//data class MonthlyDisplay(
//    val date: LocalDate, // The specific month and year this display represents (e.g., 2025-07-01)
//    val stats: SummaryStats,
//    val calendarDays: List<DayInMonthSummary>
//) {
//    /**
//     * Represents the data for a single day cell within the monthly calendar grid.
//     */
//    data class DayInMonthSummary(
//        val dayOfMonth: Int,      // The number of the day (e.g., 1, 15, 31)
//        val totalEarnings: Double,
//        val hasData: Boolean      // True if there were any dashes on this day
//    )
//
//    // A helper property to get the full name of the month for display purposes.
//    val monthName: String
//        get() = date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
//
//    val year: Int
//        get() = date.year
//
//    companion object {
//        /**
//         * A factory function to create a default/empty state for a given month and year.
//         * This is useful for initializing the UI before real data has loaded.
//         */
//        fun empty(year: Int, month: Int): MonthlyDisplay {
//            val date = LocalDate.of(year, month, 1)
//
//            // Create a list of empty day summaries, one for each day in the month.
//            val emptyDays = (1..date.lengthOfMonth()).map { day ->
//                DayInMonthSummary(
//                    dayOfMonth = day,
//                    totalEarnings = 0.0,
//                    hasData = false
//                )
//            }
//
//            return MonthlyDisplay(
//                date = date,
//                stats = SummaryStats(), // An empty stats object with all zero values
//                calendarDays = emptyDays
//            )
//        }
//    }
//}