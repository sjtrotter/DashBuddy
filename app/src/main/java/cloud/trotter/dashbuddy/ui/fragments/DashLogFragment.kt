package cloud.trotter.dashbuddy.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels // For the 'by viewModels()' delegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.data.log.dash.DashLogViewModel

class DashLogFragment : Fragment() {

    private lateinit var dashLogRecyclerView: RecyclerView
    private lateinit var dashLogAdapter: DashLogAdapter

    private val viewModel: DashLogViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_dash_log, container, false)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dashLogRecyclerView = view.findViewById(R.id.dash_log_recycler_view)

        setupRecyclerView()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        dashLogAdapter = DashLogAdapter() // Create an instance of your adapter
        val linearLayoutManager = LinearLayoutManager(context).apply {
            // stackFromEnd = true: Layout from the bottom. New items at the bottom will push old ones up.
            //                      If list is short, items align to the bottom.
            stackFromEnd = true
            // reverseLayout = false: (Default) Items are added to the end of the list,
            //                        which with stackFromEnd=true means visually at the bottom.
            reverseLayout = false
        }
        dashLogRecyclerView.apply {
            layoutManager = linearLayoutManager
            adapter = dashLogAdapter
            // Optional: for performance if item size doesn't change
            // setHasFixedSize(true)
        }
    }

    private fun observeViewModel() {
        viewModel.logMessages.observe(viewLifecycleOwner) { messages ->
            dashLogAdapter.submitList(messages) {
                // This callback is executed after the list is updated and differences are computed.
                // It's a good place to scroll, ensuring RecyclerView has processed the update.
                if (messages.isNotEmpty()) {
                    // No need for .post here usually, as submitList's callback is already well-timed.
                    dashLogRecyclerView.scrollToPosition(messages.size - 1)
                }
            }
            // Alternative, slightly more robust if issues persist:
            // dashLogAdapter.submitList(messages)
            // if (messages.isNotEmpty()) {
            //    dashLogRecyclerView.post {
            //        dashLogRecyclerView.scrollToPosition(messages.size - 1)
            //    }
            // }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // If using ViewBinding:
        // _binding = null
        // No need to nullify dashLogRecyclerView if found with findViewById directly on 'view'
    }
}
