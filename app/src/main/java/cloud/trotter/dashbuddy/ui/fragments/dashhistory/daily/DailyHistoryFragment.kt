//package cloud.trotter.dashbuddy.ui.fragments.dashhistory.daily
//
//import android.os.Bundle
//import android.view.View
//import androidx.fragment.app.Fragment
//import androidx.fragment.app.viewModels
//import androidx.lifecycle.lifecycleScope
//import androidx.viewpager2.widget.ViewPager2
//import cloud.trotter.dashbuddy.DashBuddyApplication
//import cloud.trotter.dashbuddy.R
//import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryDailyViewpagerBinding
//import cloud.trotter.dashbuddy.log.Logger
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.HistoryPage
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.SwipeDirection
//import kotlinx.coroutines.flow.first
//import kotlinx.coroutines.launch
//import java.time.LocalDate
//import java.time.temporal.ChronoUnit
//
//class DailyHistoryFragment : Fragment(R.layout.fragment_dash_history_daily_viewpager) {
//
//    private val tag = "DailyHistoryFragment"
//
//    private val stateViewModel: DashStateViewModel by viewModels({ requireParentFragment() })
//
//    // NOTE: We don't need 'dailyViewModel' anymore because the Adapter fetches data directly!
//    // We just access the repo singleton directly to pass to the adapter.
//    private val historyRepo = DashBuddyApplication.dashHistoryRepo
//
//    private var _binding: FragmentDashHistoryDailyViewpagerBinding? = null
//    private val binding get() = _binding!!
//
//    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//        super.onViewCreated(view, savedInstanceState)
//        _binding = FragmentDashHistoryDailyViewpagerBinding.bind(view)
//        Logger.d(tag, "onViewCreated")
//
//        setupViewPager()
//        setInitialPosition()
//        observeSwipeEvents()
//    }
//
//    private fun setupViewPager() {
//        val dailyAdapter = DailyAdapter(
//            fragment = this,
//            stateViewModel = this.stateViewModel,
//            historyRepo = this.historyRepo // Pass repo here
//        )
//        binding.dailyViewPager.adapter = dailyAdapter
//        binding.dailyViewPager.offscreenPageLimit = 1
//
//        // Submit placeholders
//        dailyAdapter.submitList(List(20_000) {
//            HistoryPage.Daily(
//                DailyDisplay.Companion.empty(LocalDate.now())
//            )
//        })
//
//        binding.dailyViewPager.registerOnPageChangeCallback(object :
//            ViewPager2.OnPageChangeCallback() {
//            override fun onPageSelected(position: Int) {
//                super.onPageSelected(position)
//                stateViewModel.onDailyPageSwiped(position)
//            }
//        })
//    }
//
//    // ... (Keep observeSwipeEvents and setInitialPosition exactly as they are) ...
//    private fun observeSwipeEvents() {
//        viewLifecycleOwner.lifecycleScope.launch {
//            stateViewModel.swipeEvent.collect { direction ->
//                val currentItem = binding.dailyViewPager.currentItem
//                when (direction) {
//                    SwipeDirection.NEXT -> binding.dailyViewPager.setCurrentItem(
//                        currentItem + 1,
//                        true
//                    )
//
//                    SwipeDirection.PREVIOUS -> binding.dailyViewPager.setCurrentItem(
//                        currentItem - 1,
//                        true
//                    )
//                }
//            }
//        }
//    }
//
//    private fun setInitialPosition() {
//        viewLifecycleOwner.lifecycleScope.launch {
//            // Wait for the state to be ready
//            val selectedDate = stateViewModel.selectedDate.first()
//
//            val daysBetween =
//                ChronoUnit.DAYS.between(DashStateViewModel.REFERENCE_DAY_DATE, selectedDate)
//
//            val targetPosition = (DailyAdapter.START_POSITION + daysBetween).toInt()
//
//            Logger.i(tag, "Setting initial daily position to $targetPosition for $selectedDate")
//            binding.dailyViewPager.setCurrentItem(targetPosition, false)
//        }
//    }
//
//    override fun onDestroyView() {
//        super.onDestroyView()
//        _binding = null
//    }
//}