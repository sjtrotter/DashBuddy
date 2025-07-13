package cloud.trotter.dashbuddy.ui.fragments.dashhistory.daily

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
import java.time.LocalDate
import java.util.Calendar

@OptIn(ExperimentalCoroutinesApi::class)
class DailyViewModel(
    private val historyRepo: DashHistoryRepo,
    stateViewModel: DashStateViewModel
) : ViewModel() {

    /**
     * A single, reactive flow that provides the complete DailyDisplay model.
     * It automatically updates whenever the selected year, month, or day changes.
     */
    val dailyDisplay: StateFlow<DailyDisplay> =
        combine(
            stateViewModel.selectedYear,
            stateViewModel.selectedMonth,
            stateViewModel.selectedDay
        ) { year, month, day ->
            // Combine the date parts into a single LocalDate object to trigger the flow.
            // Provide sensible defaults if any part is null.
            val cal = Calendar.getInstance()
            LocalDate.of(
                year,
                month ?: (cal.get(Calendar.MONTH) + 1),
                day ?: cal.get(Calendar.DAY_OF_MONTH)
            )
        }.flatMapLatest { date ->
            historyRepo.getDailyDisplayFlow(date)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DailyDisplay.empty(LocalDate.now())
        )
}
