package cloud.trotter.dashbuddy.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import cloud.trotter.dashbuddy.data.log.dash.DashLogAdapter
import cloud.trotter.dashbuddy.data.log.dash.DashLogViewModel
import cloud.trotter.dashbuddy.databinding.FragmentDashLogBinding // Import the binding class

class DashLogFragment : Fragment() {

    private var _binding: FragmentDashLogBinding? = null
    private val binding get() = _binding!!

    private lateinit var dashLogAdapter: DashLogAdapter
    private val viewModel: DashLogViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        dashLogAdapter = DashLogAdapter()
        val linearLayoutManager = LinearLayoutManager(context).apply {
            stackFromEnd = true
            reverseLayout = false
        }
        binding.dashLogRecyclerView.apply {
            layoutManager = linearLayoutManager
            adapter = dashLogAdapter
        }
    }

    private fun observeViewModel() {
        viewModel.logMessages.observe(viewLifecycleOwner) { messages ->
            dashLogAdapter.submitList(messages) {
                if (messages.isNotEmpty()) {
                    binding.dashLogRecyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
