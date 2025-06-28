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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
                        app.appPayRepo,
                        app.tipRepo
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
            onDashClicked = { dashId -> viewModel.toggleDashExpanded(dashId) },
            onOfferClicked = { dashId, offerSummary ->
                viewModel.toggleOfferExpanded(
                    dashId,
                    offerSummary
                )
            }
        )
        binding.dashHistoryRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = dashHistoryAdapter
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.dashSummaries.collectLatest { summaries ->
                dashHistoryAdapter.submitList(summaries)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}