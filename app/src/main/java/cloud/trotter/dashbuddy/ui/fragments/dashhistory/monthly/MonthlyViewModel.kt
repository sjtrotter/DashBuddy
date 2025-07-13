package cloud.trotter.dashbuddy.ui.fragments.dashhistory.monthly

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashHistoryRepo
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

@OptIn(ExperimentalCoroutinesApi::class)
class MonthlyViewModel(
    private val historyRepo: DashHistoryRepo,
    stateViewModel: DashStateViewModel
) : ViewModel() {

    /**
     * A single, reactive flow that provides the complete MonthlyDisplay model.
     * It automatically updates whenever the selected year or month changes.
     */
    val monthlyDisplay: StateFlow<MonthlyDisplay> =
        combine(
            stateViewModel.selectedYear,
            stateViewModel.selectedMonth
        ) { year, month ->
            // Combine year and month into a Pair to trigger the flow
            year to (month ?: (Calendar.getInstance().get(Calendar.MONTH) + 1))
        }.flatMapLatest { (year, month) ->
            historyRepo.getMonthlyDisplayFlow(year, month)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MonthlyDisplay.empty(
                stateViewModel.selectedYear.value,
                stateViewModel.selectedMonth.value ?: 1
            )
        )
}
