package cloud.trotter.dashbuddy.ui.fragments.dashhistory.annual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashHistoryRepo
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class AnnualViewModel(
    private val historyRepo: DashHistoryRepo,
    stateViewModel: DashStateViewModel // It needs the shared state to know which year to show
) : ViewModel() {

    /**
     * A single, reactive flow that provides the complete AnnualDisplay model.
     * It automatically updates whenever the selected year in the DashStateViewModel changes.
     */
    val annualDisplay: StateFlow<AnnualDisplay> =
        stateViewModel.selectedYear.flatMapLatest { year ->
            historyRepo.getAnnualDisplayFlow(year)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AnnualDisplay.empty(stateViewModel.selectedYear.value)
        )
}