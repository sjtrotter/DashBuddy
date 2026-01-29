//package cloud.trotter.dashbuddy.ui.bubble.fragments
//
////import android.view.Menu
////import android.view.MenuInflater
////import androidx.core.view.MenuProvider
////import androidx.lifecycle.Lifecycle
//import android.os.Bundle
//import android.view.LayoutInflater
//import android.view.View
//import android.view.ViewGroup
//import androidx.appcompat.app.AppCompatActivity
//import androidx.appcompat.content.res.AppCompatResources.getDrawable
//import androidx.fragment.app.Fragment
//import androidx.fragment.app.viewModels
//import androidx.recyclerview.widget.LinearLayoutManager
//import cloud.trotter.dashbuddy.DashBuddyApplication
//import cloud.trotter.dashbuddy.R
//import cloud.trotter.dashbuddy.data.log.dash.DashLogViewModel
//import cloud.trotter.dashbuddy.databinding.FragmentDashLogBinding
//import cloud.trotter.dashbuddy.ui.bubble.BubbleActivity
//import cloud.trotter.dashbuddy.ui.bubble.adapters.DashLogAdapter
//import dagger.hilt.android.AndroidEntryPoint
//
//@AndroidEntryPoint
//class DashLogFragment : Fragment() {
//
//    private var _binding: FragmentDashLogBinding? = null
//    private val binding get() = _binding!!
//    private lateinit var dashLogAdapter: DashLogAdapter
//    private val viewModel: DashLogViewModel by viewModels()
//
//    override fun onCreateView(
//        inflater: LayoutInflater, container: ViewGroup?,
//        savedInstanceState: Bundle?
//    ): View {
//        _binding = FragmentDashLogBinding.inflate(inflater, container, false)
//        return binding.root
//    }
//
//    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//        super.onViewCreated(view, savedInstanceState)
//        setupRecyclerView()
//        observeViewModel()
//        setupMenu()
//    }
//
//    private fun setupMenu() {
//        // Set the title on the activity's toolbar for this fragment
//        (activity as? AppCompatActivity)?.supportActionBar?.title = "Dash Log"
//
//        // Set the navigation icon for the toolbar
//        val toolbar =
//            (activity as? BubbleActivity)?.findViewById<androidx.appcompat.widget.Toolbar>(R.id.bubble_toolbar)
//        toolbar?.navigationIcon =
//            getDrawable(DashBuddyApplication.context, R.drawable.ic_menu_toolbar_dash_log)
//
//
//        // This fragment has no menu items, so we don't need to add a MenuProvider.
//    }
//
//    private fun setupRecyclerView() {
//        dashLogAdapter = DashLogAdapter()
//        val linearLayoutManager = LinearLayoutManager(context).apply {
//            stackFromEnd = true
//            reverseLayout = false
//        }
//        binding.dashLogRecyclerView.apply {
//            layoutManager = linearLayoutManager
//            adapter = dashLogAdapter
//        }
//    }
//
//    private fun observeViewModel() {
//        viewModel.logMessages.observe(viewLifecycleOwner) { messages ->
//            dashLogAdapter.submitList(messages) {
//                if (messages.isNotEmpty()) {
//                    binding.dashLogRecyclerView.scrollToPosition(messages.size - 1)
//                }
//            }
//        }
//    }
//
//    override fun onDestroyView() {
//        super.onDestroyView()
//        _binding = null
//    }
//}