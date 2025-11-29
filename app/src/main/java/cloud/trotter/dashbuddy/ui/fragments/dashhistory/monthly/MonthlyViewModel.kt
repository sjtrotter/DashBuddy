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

@OptIn(ExperimentalCoroutinesApi::class)
class MonthlyViewModel(
    private val historyRepo: DashHistoryRepo,
    stateViewModel: DashStateViewModel
) : ViewModel() {

    // Optimized: Only reloads when Year or Month changes.
    // Ignores Day changes because flows are distinct.
    val monthlyDisplay: StateFlow<MonthlyDisplay> =
        combine(
            stateViewModel.selectedYear,
            stateViewModel.selectedMonth
        ) { year, month ->
            year to month
        }.flatMapLatest { (year, month) ->
            historyRepo.getMonthlyDisplayFlow(year, month)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MonthlyDisplay.empty(
                stateViewModel.selectedYear.value,
                stateViewModel.selectedMonth.value
            )
        )
}