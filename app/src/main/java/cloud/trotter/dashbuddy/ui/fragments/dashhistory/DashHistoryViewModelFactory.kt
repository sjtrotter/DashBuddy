package cloud.trotter.dashbuddy.ui.fragments.dashhistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cloud.trotter.dashbuddy.data.models.dash.history.DashHistoryRepository

class DashHistoryViewModelFactory(
    private val dashHistoryRepository: DashHistoryRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DashHistoryViewModel::class.java)) {
            return DashHistoryViewModel(dashHistoryRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}