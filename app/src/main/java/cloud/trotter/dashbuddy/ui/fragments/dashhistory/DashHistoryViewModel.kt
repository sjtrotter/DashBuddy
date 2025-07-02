package cloud.trotter.dashbuddy.ui.fragments.dashhistory

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import cloud.trotter.dashbuddy.data.models.dash.history.DashHistoryRepository
import cloud.trotter.dashbuddy.data.models.dash.history.DaySummary

class DashHistoryViewModel(
    dashHistoryRepository: DashHistoryRepository
) : ViewModel() {

    /**
     * Exposes a LiveData stream of the fully processed and formatted day summaries.
     * The asLiveData() extension automatically handles the collection of the Flow
     * from the repository in a lifecycle-aware manner.
     */
    val daySummaries: LiveData<List<DaySummary>> =
        dashHistoryRepository.getDashHistorySummaries().asLiveData()

}