package cloud.trotter.dashbuddy.ui.fragments.dashhistory.daily

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashHistoryRepo
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel

/**
 * A factory for creating instances of DailyViewModel.
 * It provides the necessary dependencies (the repository and the shared state ViewModel).
 */
class DailyViewModelFactory(
    private val historyRepo: DashHistoryRepo,
    private val stateViewModel: DashStateViewModel
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DailyViewModel::class.java)) {
            return DailyViewModel(historyRepo, stateViewModel) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}