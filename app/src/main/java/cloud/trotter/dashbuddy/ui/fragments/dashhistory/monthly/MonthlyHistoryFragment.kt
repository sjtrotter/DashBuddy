package cloud.trotter.dashbuddy.ui.fragments.dashhistory.monthly

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryMonthlyViewpagerBinding
import cloud.trotter.dashbuddy.log.Logger
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.HistoryPage
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.SwipeDirection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class MonthlyHistoryFragment : Fragment(R.layout.fragment_dash_history_monthly_viewpager) {

    private val tag = "MonthlyHistoryFragment"

    private val stateViewModel: DashStateViewModel by viewModels({ requireParentFragment() })
    private val monthlyViewModel: MonthlyViewModel by viewModels {
        MonthlyViewModelFactory(
            DashBuddyApplication.Companion.dashHistoryRepo,
            stateViewModel
        )
    }

    private var _binding: FragmentDashHistoryMonthlyViewpagerBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDashHistoryMonthlyViewpagerBinding.bind(view)
        Logger.d(tag, "onViewCreated")

        setupViewPager()
        setInitialPosition()
        observeSwipeEvents()
    }

    private fun setupViewPager() {
        val monthlyAdapter = MonthlyAdapter(
            fragment = this,
            stateViewModel = this.stateViewModel,
            monthlyViewModel = this.monthlyViewModel,
            onDayClicked = { day ->
                stateViewModel.selectDay(day)
            }
        )
        binding.monthlyViewPager.adapter = monthlyAdapter

        // FIX: Use valid date parts for the placeholder to prevent the crash.
        // The actual date doesn't matter, as long as it's a real one.
        monthlyAdapter.submitList(List(20_000) {
            HistoryPage.Monthly(MonthlyDisplay.Companion.empty(2020, 1))
        })

        binding.monthlyViewPager.registerOnPageChangeCallback(object :
            ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                stateViewModel.onMonthPageSwiped(position)
            }
        })
    }

    private fun observeSwipeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            stateViewModel.swipeEvent.collect { direction ->
                val currentItem = binding.monthlyViewPager.currentItem
                when (direction) {
                    SwipeDirection.NEXT -> binding.monthlyViewPager.setCurrentItem(
                        currentItem + 1,
                        true
                    )

                    SwipeDirection.PREVIOUS -> binding.monthlyViewPager.setCurrentItem(
                        currentItem - 1,
                        true
                    )
                }
            }
        }
    }

    private fun setInitialPosition() {
        viewLifecycleOwner.lifecycleScope.launch {
            val year = stateViewModel.selectedYear.first()
            val month = stateViewModel.selectedMonth.first() ?: 1
            val selectedDate = LocalDate.of(year, month, 1)
            val monthsBetween =
                ChronoUnit.MONTHS.between(DashStateViewModel.Companion.REFERENCE_MONTH_DATE, selectedDate)
            val targetPosition = (MonthlyAdapter.START_POSITION + monthsBetween).toInt()

            Logger.i(tag, "Setting initial monthly position to $targetPosition for $year-$month")
            binding.monthlyViewPager.setCurrentItem(targetPosition, false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}