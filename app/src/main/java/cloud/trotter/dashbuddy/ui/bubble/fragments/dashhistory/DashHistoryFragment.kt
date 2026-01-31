//package cloud.trotter.dashbuddy.ui.fragments.dashhistory
//
//import android.os.Bundle
//import android.view.Menu
//import android.view.MenuInflater
//import android.view.MenuItem
//import android.view.View
//import android.widget.Toast
//import androidx.activity.OnBackPressedCallback
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.view.MenuProvider
//import androidx.fragment.app.Fragment
//import androidx.fragment.app.viewModels
//import androidx.lifecycle.Lifecycle
//import androidx.lifecycle.lifecycleScope
//import cloud.trotter.dashbuddy.R
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.annual.AnnualHistoryFragment
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.daily.DailyHistoryFragment
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.monthly.MonthlyHistoryFragment
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.HistoryViewType
//import kotlinx.coroutines.flow.collect
//import kotlinx.coroutines.flow.collectLatest
//import kotlinx.coroutines.flow.combine
//import kotlinx.coroutines.launch
//import java.text.DateFormatSymbols
//import cloud.trotter.dashbuddy.log.Logger as Log
//
//class DashHistoryFragment : Fragment(R.layout.fragment_dash_history) {
//
//    private val tag = "DashHistoryFragment"
//    private val stateViewModel: DashStateViewModel by viewModels()
//
//    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//        super.onViewCreated(view, savedInstanceState)
//        Log.d("onViewCreated")
//
//        setupMenu()
//        observeViewTypeChanges()
//        setupOnBackPressed()
//    }
//
//    private fun observeViewTypeChanges() {
//        viewLifecycleOwner.lifecycleScope.launch {
//            stateViewModel.currentViewType.collectLatest { viewType ->
//                Log.i("ViewType changed to $viewType, swapping child fragment.")
//                val childFragment = when (viewType) {
//                    HistoryViewType.ANNUAL -> AnnualHistoryFragment()
//                    HistoryViewType.MONTHLY -> MonthlyHistoryFragment()
//                    HistoryViewType.DAILY -> DailyHistoryFragment()
//                }
//                childFragmentManager.beginTransaction()
//                    .replace(R.id.history_child_fragment_container, childFragment)
//                    .commit()
//            }
//        }
//    }
//
//    private fun setupOnBackPressed() {
//        val callback = object : OnBackPressedCallback(true) { // true = enabled
//            override fun handleOnBackPressed() {
//                // Check the state from the ViewModel
//                if (stateViewModel.currentViewType.value != HistoryViewType.ANNUAL) {
//                    // If we are in a drilled-down state (Monthly or Daily),
//                    // our custom back action is to navigate up.
//                    stateViewModel.navigateUp()
//                } else {
//                    // If we are at the top level (Annual), we want the default
//                    // system back behavior to occur. We disable our callback,
//                    // trigger the default back press, and then re-enable it.
//                    isEnabled = false
//                    requireActivity().onBackPressedDispatcher.onBackPressed()
//                    isEnabled = true
//                }
//            }
//        }
//        // Add the callback to the dispatcher, tied to the fragment's lifecycle
//        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
//    }
//
//    private fun setupMenu() {
//        Log.d("setupMenu")
//        val menuHost = requireActivity()
//        menuHost.addMenuProvider(object : MenuProvider {
//            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
//                menuInflater.inflate(R.menu.dash_history_menu, menu)
//            }
//
//            override fun onPrepareMenu(menu: Menu) {
//                // FIX: This observer now combines all relevant state flows.
//                // It will now trigger updateToolbar() whenever the viewType, year, month, OR day changes.
//                viewLifecycleOwner.lifecycleScope.launch {
//                    combine(
//                        stateViewModel.currentViewType,
//                        stateViewModel.selectedYear,
//                        stateViewModel.selectedMonth,
//                        stateViewModel.selectedDay
//                    ) { _, _, _, _ ->
//                        // We don't need the values here, we just need this block to run on any change.
//                        updateToolbar()
//                    }.collect() // Use collect() to start the flow
//                }
//            }
//
//            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
//                return when (menuItem.itemId) {
//                    android.R.id.home -> {
//                        stateViewModel.navigateUp(); true
//                    }
//
//                    R.id.menu_prev -> {
//                        stateViewModel.onPreviousClicked(); true
//                    }
//
//                    R.id.menu_today -> {
//                        stateViewModel.navigateToToday(); true
//                    }
//
//                    R.id.menu_next -> {
//                        stateViewModel.onNextClicked(); true
//                    }
//
//                    R.id.menu_export -> {
//                        Toast.makeText(context, "Export Clicked", Toast.LENGTH_SHORT).show(); true
//                    }
//
//                    else -> false
//                }
//            }
//        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
//    }
//
//    private fun updateToolbar() {
//        val activity = activity as? AppCompatActivity ?: return
//        val viewType = stateViewModel.currentViewType.value
//        val year = stateViewModel.selectedYear.value
//        val month = stateViewModel.selectedMonth.value
//        val day = stateViewModel.selectedDay.value
//
//        val title = when (viewType) {
//            HistoryViewType.ANNUAL -> "$year"
//            HistoryViewType.MONTHLY -> "${DateFormatSymbols.getInstance().months[(month ?: 1) - 1]} $year"
//            HistoryViewType.DAILY -> "${DateFormatSymbols.getInstance().months[(month ?: 1) - 1]} $day, $year"
//        }
//        activity.supportActionBar?.title = title
//        Log.v("Toolbar title updated to: $title")
//
//        val navIconRes = when (viewType) {
//            HistoryViewType.ANNUAL -> R.drawable.ic_history_year
//            HistoryViewType.MONTHLY -> R.drawable.ic_history_month
//            HistoryViewType.DAILY -> R.drawable.ic_history_today
//        }
//        activity.supportActionBar?.setHomeAsUpIndicator(navIconRes)
//        activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
//    }
//}