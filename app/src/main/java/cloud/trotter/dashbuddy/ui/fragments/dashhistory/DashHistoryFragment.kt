package cloud.trotter.dashbuddy.ui.fragments.dashhistory

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryBinding
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.adapters.DashHistoryAdapter

class DashHistoryFragment : Fragment() {

    private var _binding: FragmentDashHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashHistoryViewModel by viewModels {
        // Get the facade repository
        val dashHistoryRepository = DashBuddyApplication.dashHistoryRepository
        // Provide the facade repository to the ViewModel via the Factory
        DashHistoryViewModelFactory(dashHistoryRepository)
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
        dashHistoryAdapter = DashHistoryAdapter()
        binding.dashHistoryRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = dashHistoryAdapter
            // Optional: Add the sticky header ItemDecoration here later
        }
    }

    private fun observeViewModel() {
        // Observe the LiveData from the ViewModel.
        // When the data changes (because a Flow emitted a new list),
        // this block will execute and update the adapter.
        viewModel.daySummaries.observe(viewLifecycleOwner) { daySummaries ->
            // Let the ListAdapter handle the diffing and animations
            dashHistoryAdapter.submitList(daySummaries)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Avoid memory leaks by nulling out the binding
        _binding = null
    }
}