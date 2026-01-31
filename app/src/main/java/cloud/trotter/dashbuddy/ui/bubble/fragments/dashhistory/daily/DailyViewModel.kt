//package cloud.trotter.dashbuddy.ui.fragments.dashhistory.daily
//
//import androidx.lifecycle.ViewModel
//import androidx.lifecycle.viewModelScope
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashHistoryRepo
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
//import kotlinx.coroutines.ExperimentalCoroutinesApi
//import kotlinx.coroutines.flow.SharingStarted
//import kotlinx.coroutines.flow.StateFlow
//import kotlinx.coroutines.flow.flatMapLatest
//import kotlinx.coroutines.flow.stateIn
//import java.time.LocalDate
//
//@OptIn(ExperimentalCoroutinesApi::class)
//class DailyViewModel(
//    private val historyRepo: DashHistoryRepo,
//    stateViewModel: DashStateViewModel
//) : ViewModel() {
//
//    // CRASH FIXED: No more manual combine logic.
//    // We observe the "Atomic Date" which is guaranteed to be valid.
//    val dailyDisplay: StateFlow<DailyDisplay> =
//        stateViewModel.selectedDate.flatMapLatest { date ->
//            historyRepo.getDailyDisplayFlow(date)
//        }.stateIn(
//            scope = viewModelScope,
//            started = SharingStarted.WhileSubscribed(5000),
//            initialValue = DailyDisplay.empty(LocalDate.now())
//        )
//}