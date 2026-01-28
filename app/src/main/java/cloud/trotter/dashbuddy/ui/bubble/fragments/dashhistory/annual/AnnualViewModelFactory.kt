//package cloud.trotter.dashbuddy.ui.fragments.dashhistory.annual
//
//import androidx.lifecycle.ViewModel
//import androidx.lifecycle.ViewModelProvider
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashHistoryRepo
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
//
//class AnnualViewModelFactory(
//    private val historyRepo: DashHistoryRepo,
//    private val stateViewModel: DashStateViewModel // Add the shared state ViewModel as a dependency
//) : ViewModelProvider.Factory {
//
//    @Suppress("UNCHECKED_CAST")
//    override fun <T : ViewModel> create(modelClass: Class<T>): T {
//        if (modelClass.isAssignableFrom(AnnualViewModel::class.java)) {
//            // Pass both dependencies to the AnnualViewModel constructor
//            return AnnualViewModel(historyRepo, stateViewModel) as T
//        }
//        throw IllegalArgumentException("Unknown ViewModel class")
//    }
//}
