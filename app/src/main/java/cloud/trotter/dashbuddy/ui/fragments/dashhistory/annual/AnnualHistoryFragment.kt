//package cloud.trotter.dashbuddy.ui.fragments.dashhistory.annual
//
//import android.os.Bundle
//import android.view.View
//import androidx.fragment.app.Fragment
//import androidx.fragment.app.viewModels
//import androidx.lifecycle.lifecycleScope
//import androidx.viewpager2.widget.ViewPager2
//import cloud.trotter.dashbuddy.R
//import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryAnnualViewpagerBinding
//import cloud.trotter.dashbuddy.log.Logger
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.HistoryPage
//import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.SwipeDirection
//import kotlinx.coroutines.flow.first
//import kotlinx.coroutines.launch
//
//class AnnualHistoryFragment : Fragment(R.layout.fragment_dash_history_annual_viewpager) {
//
//    private val tag = "AnnualHistoryFragment"
//    private val stateViewModel: DashStateViewModel by viewModels({ requireParentFragment() })
//
//    private var _binding: FragmentDashHistoryAnnualViewpagerBinding? = null
//    private val binding get() = _binding!!
//
//    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//        super.onViewCreated(view, savedInstanceState)
//        _binding = FragmentDashHistoryAnnualViewpagerBinding.bind(view)
//        setupViewPager()
//        setInitialPosition()
//        observeSwipeEvents()
//    }
//
//    private fun setupViewPager() {
//        val annualAdapter = AnnualAdapter(
//            fragment = this,
//            stateViewModel = this.stateViewModel,
//            onMonthClicked = { month -> stateViewModel.selectMonth(month) }
//        )
//        binding.annualViewPager.adapter = annualAdapter
//        binding.annualViewPager.offscreenPageLimit = 1
//
//        // Placeholder list
//        annualAdapter.submitList(List(20_000) { HistoryPage.Annual(AnnualDisplay.empty(2020)) })
//
//        binding.annualViewPager.registerOnPageChangeCallback(object :
//            ViewPager2.OnPageChangeCallback() {
//            override fun onPageSelected(position: Int) {
//                super.onPageSelected(position)
//                stateViewModel.onYearPageSwiped(2020 + (position - AnnualAdapter.START_POSITION))
//            }
//        })
//    }
//
//    // ... (Keep observeSwipeEvents and setInitialPosition as they are) ...
//    private fun observeSwipeEvents() {
//        viewLifecycleOwner.lifecycleScope.launch {
//            stateViewModel.swipeEvent.collect { direction ->
//                val currentItem = binding.annualViewPager.currentItem
//                when (direction) {
//                    SwipeDirection.NEXT -> binding.annualViewPager.setCurrentItem(
//                        currentItem + 1,
//                        true
//                    )
//
//                    SwipeDirection.PREVIOUS -> binding.annualViewPager.setCurrentItem(
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
//            val selectedYear = stateViewModel.selectedYear.first()
//            val targetPosition = AnnualAdapter.START_POSITION + (selectedYear - 2020)
//            Logger.i(tag, "Setting initial annual position to $targetPosition for $selectedYear")
//            binding.annualViewPager.setCurrentItem(targetPosition, false)
//        }
//    }
//
//    override fun onDestroyView() {
//        super.onDestroyView()
//        _binding = null
//    }
//}