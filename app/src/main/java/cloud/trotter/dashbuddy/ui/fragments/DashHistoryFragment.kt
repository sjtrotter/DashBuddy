package cloud.trotter.dashbuddy.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryBinding
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DashHistoryFragment : Fragment() {

    private var _binding: FragmentDashHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashHistoryViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(DashHistoryViewModel::class.java)) {
                    val app = DashBuddyApplication
                    return DashHistoryViewModel(
                        app.dashRepo,
                        app.offerRepo,
                        app.orderRepo,
                        app.tipRepo,
                        app.appPayRepo
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }

    private lateinit var dashHistoryAdapter: DashHistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        dashHistoryAdapter = DashHistoryAdapter(
            onMonthClicked = { timestamp -> showMonthPicker(timestamp) },
            onDayClicked = { dayId -> viewModel.toggleDayExpansion(dayId) },
            onDashClicked = { dashId -> viewModel.toggleDashExpansion(dashId) }
        )

        val layoutManager = LinearLayoutManager(context)
        binding.dashHistoryRecyclerView.apply {
            this.layoutManager = layoutManager
            adapter = dashHistoryAdapter
            // Add the sticky header decoration
            addItemDecoration(
                StickyMonthHeaderDecoration(
                    dashHistoryAdapter,
                    binding.root
                )
            )
            addItemDecoration(StickyHistoryHeaderDecoration(dashHistoryAdapter))

        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.historyListItems.collectLatest { summaries ->
                dashHistoryAdapter.submitList(summaries)
            }
        }
    }

    private fun showMonthPicker(currentTimestamp: Long) {
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Select a Month")
            .setSelection(currentTimestamp)
            .build()

        datePicker.addOnPositiveButtonClickListener { selection ->
            // Find the position of the corresponding month header and scroll to it
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = selection
            val targetMonthYear =
                SimpleDateFormat("MMMM yyyy", Locale.US).format(calendar.time).uppercase()

            val position = dashHistoryAdapter.currentList.indexOfFirst {
                it is cloud.trotter.dashbuddy.data.models.MonthHeaderItem && it.monthYear == targetMonthYear
            }
            if (position != -1) {
                (binding.dashHistoryRecyclerView.layoutManager as LinearLayoutManager)
                    .scrollToPositionWithOffset(position, 0)
            }
        }

        datePicker.show(childFragmentManager, datePicker.toString())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.dashHistoryRecyclerView.adapter = null
        _binding = null
    }
}