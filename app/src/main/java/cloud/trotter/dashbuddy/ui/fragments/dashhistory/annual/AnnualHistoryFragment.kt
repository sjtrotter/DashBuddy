package cloud.trotter.dashbuddy.ui.fragments.dashhistory.annual

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryAnnualViewpagerBinding
import cloud.trotter.dashbuddy.log.Logger
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.HistoryPage
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.SwipeDirection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

class AnnualHistoryFragment : Fragment(R.layout.fragment_dash_history_annual_viewpager) {

    private val tag = "AnnualHistoryFragment"

    // Get the SHARED ViewModel from the parent activity/fragment
    private val stateViewModel: DashStateViewModel by viewModels({ requireParentFragment() })

    // Get its OWN specialist ViewModel using the new Factory
    private val annualViewModel: AnnualViewModel by viewModels {
        AnnualViewModelFactory(DashBuddyApplication.Companion.dashHistoryRepo, stateViewModel)
    }

    private var _binding: FragmentDashHistoryAnnualViewpagerBinding? = null
    private val binding get() = _binding!!

    private lateinit var annualAdapter: AnnualAdapter
    private val initialYear = Calendar.getInstance().get(Calendar.YEAR)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDashHistoryAnnualViewpagerBinding.bind(view)
        Logger.d(tag, "onViewCreated")

        setupViewPager()
        setInitialPosition()
        observeSwipeEvents()
    }

    private fun observeSwipeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            stateViewModel.swipeEvent.collect { direction ->
                val currentItem = binding.annualViewPager.currentItem
                when (direction) {
                    SwipeDirection.NEXT -> {
                        Logger.d(tag, "Next swipe event received")
                        binding.annualViewPager.setCurrentItem(currentItem + 1, true)
                    }

                    SwipeDirection.PREVIOUS -> {
                        Logger.d(tag, "Previous swipe event received")
                        binding.annualViewPager.setCurrentItem(currentItem - 1, true)
                    }
                }
            }
        }
    }

    private fun setupViewPager() {
        annualAdapter = AnnualAdapter(
            fragment = this,
            stateViewModel = this.stateViewModel,
            annualViewModel = this.annualViewModel, // Pass the ViewModel to the adapter
            onMonthClicked = { month ->
                stateViewModel.selectMonth(month)
            }
        )
        binding.annualViewPager.adapter = annualAdapter

        annualAdapter.submitList(List(20_000) {
            HistoryPage.Annual(AnnualDisplay.Companion.empty(0))
        })

        binding.annualViewPager.registerOnPageChangeCallback(object :
            ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val year = initialYear + (position - AnnualAdapter.START_POSITION)
                stateViewModel.onYearPageSwiped(year)
            }
        })
    }

    private fun setInitialPosition() {
        viewLifecycleOwner.lifecycleScope.launch {
            val currentSelectedYear = stateViewModel.selectedYear.first()
            val targetPosition = AnnualAdapter.START_POSITION + (currentSelectedYear - initialYear)
            Logger.i(
                tag,
                "Setting initial annual position to $targetPosition for year $currentSelectedYear"
            )
            binding.annualViewPager.setCurrentItem(targetPosition, false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}