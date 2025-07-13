package cloud.trotter.dashbuddy.ui.fragments.dashhistory.monthly

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashHistoryRepo
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel

/**
 * A factory for creating instances of MonthlyViewModel.
 * It provides the necessary dependencies (the repository and the shared state ViewModel).
 */
class MonthlyViewModelFactory(
    private val historyRepo: DashHistoryRepo,
    private val stateViewModel: DashStateViewModel
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MonthlyViewModel::class.java)) {
            return MonthlyViewModel(historyRepo, stateViewModel) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}