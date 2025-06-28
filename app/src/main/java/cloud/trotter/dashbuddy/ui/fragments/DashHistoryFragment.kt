package cloud.trotter.dashbuddy.ui.fragments

import android.os.Bundle
import android.util.Log
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
            },
            onOfferInfoClicked = { offerSummary ->
                // TODO: Find the actual offer stats and display them in a dialog
                Log.d("DashHistoryFragment", "Info icon clicked for offer: $offerSummary")
                showOfferInfoDialog(offerSummary)
            }
        )
        binding.dashHistoryRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = dashHistoryAdapter
            // Prevents nested RecyclerViews from creating their own scroll behavior
            isNestedScrollingEnabled = false
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.dashSummaries.collectLatest { summaries ->
                dashHistoryAdapter.submitList(summaries)
            }
        }
    }

    private fun showOfferInfoDialog(offerSummary: String) {
        // This is a placeholder. A real implementation would fetch the detailed
        // "Offer Stats" from the ViewModel based on the offerSummary (which acts as a unique ID here)
        // and format them nicely in the dialog message.
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Offer Details")
            .setMessage("Showing original stats for:\n$offerSummary\n\n(Full stats implementation is pending)")
            .setPositiveButton("OK", null)
            .show()
    }


    override fun onDestroyView() {
        super.onDestroyView()
        // To avoid memory leaks, especially with RecyclerView adapters
        binding.dashHistoryRecyclerView.adapter = null
        _binding = null
    }
}