package cloud.trotter.dashbuddy.ui.fragments.dashhistory.common

import cloud.trotter.dashbuddy.ui.fragments.dashhistory.annual.AnnualDisplay
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.daily.DailyDisplay
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.monthly.MonthlyDisplay

sealed class HistoryPage(val viewType: HistoryViewType, open val id: String) {
    data class Annual(val summary: AnnualDisplay) :
        HistoryPage(
            HistoryViewType.ANNUAL,
            "annual_${summary.year}"
        )

    data class Monthly(val summary: MonthlyDisplay) :
        HistoryPage(
            HistoryViewType.MONTHLY,
            "monthly_${summary.date.year}_${summary.date.month}"
        )

    data class Daily(val summary: DailyDisplay) :
        HistoryPage(
            HistoryViewType.DAILY,
            "daily_${summary.date.year}_${summary.date.month}_${summary.date.dayOfMonth}"
        )

}