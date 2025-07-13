package cloud.trotter.dashbuddy.ui.fragments.dashhistory.daily

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryDailyViewpagerBinding
import cloud.trotter.dashbuddy.log.Logger
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashStateViewModel
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.HistoryPage
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.SwipeDirection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class DailyHistoryFragment : Fragment(R.layout.fragment_dash_history_daily_viewpager) {

    private val tag = "DailyHistoryFragment"

    // Get the shared ViewModel from the parent fragment
    private val stateViewModel: DashStateViewModel by viewModels({ requireParentFragment() })

    // Get this fragment's own specialist ViewModel using the new Factory
    private val dailyViewModel: DailyViewModel by viewModels {
        DailyViewModelFactory(
            DashBuddyApplication.Companion.dashHistoryRepo, // Assuming this is your repo instance
            stateViewModel
        )
    }

    private var _binding: FragmentDashHistoryDailyViewpagerBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDashHistoryDailyViewpagerBinding.bind(view)
        Logger.d(tag, "onViewCreated")

        setupViewPager()
        setInitialPosition()
        observeSwipeEvents()
    }

    private fun setupViewPager() {
        val dailyAdapter = DailyAdapter(
            fragment = this,
            stateViewModel = this.stateViewModel,
            dailyViewModel = this.dailyViewModel
        )
        binding.dailyViewPager.adapter = dailyAdapter

        // Submit a placeholder list to enable "infinite" scrolling
        dailyAdapter.submitList(List(20_000) { HistoryPage.Daily(
            DailyDisplay.Companion.empty(
                LocalDate.now())) })

        binding.dailyViewPager.registerOnPageChangeCallback(object :
            ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                stateViewModel.onDailyPageSwiped(position)
            }
        })
    }

    private fun observeSwipeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            stateViewModel.swipeEvent.collect { direction ->
                val currentItem = binding.dailyViewPager.currentItem
                when (direction) {
                    SwipeDirection.NEXT -> binding.dailyViewPager.setCurrentItem(
                        currentItem + 1,
                        true
                    )

                    SwipeDirection.PREVIOUS -> binding.dailyViewPager.setCurrentItem(
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
            val day = stateViewModel.selectedDay.first() ?: 1

            val selectedDate = LocalDate.of(year, month, day)

            // Calculate how many days have passed between our fixed reference date and the selected date
            val daysBetween =
                ChronoUnit.DAYS.between(DashStateViewModel.Companion.REFERENCE_DAY_DATE, selectedDate)

            val targetPosition = (DailyAdapter.START_POSITION + daysBetween).toInt()

            Logger.i(tag, "Setting initial daily position to $targetPosition for $selectedDate")
            binding.dailyViewPager.setCurrentItem(targetPosition, false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}