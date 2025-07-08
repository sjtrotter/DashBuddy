package cloud.trotter.dashbuddy.ui.fragments.dashhistory

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.databinding.FragmentDashHistoryBinding
import cloud.trotter.dashbuddy.ui.activities.BubbleActivity
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.adapters.DashHistoryAdapter

class DashHistoryFragment : Fragment() {

    private var _binding: FragmentDashHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashHistoryViewModel by viewModels {
        val dashHistoryRepository = DashBuddyApplication.dashHistoryRepository
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
        setupMenu()
    }

    private fun setupMenu() {
        (activity as? AppCompatActivity)?.supportActionBar?.title =
            "History" // Clear title to make room for menu items

        // Set the navigation icon for the toolbar
        val toolbar =
            (activity as? BubbleActivity)?.findViewById<androidx.appcompat.widget.Toolbar>(R.id.bubble_toolbar)
        toolbar?.navigationIcon =
            getDrawable(DashBuddyApplication.context, R.drawable.ic_menu_toolbar_history)

        val menuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.dash_history_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.menu_prev_month -> {
                        Toast.makeText(context, "Previous Month Clicked", Toast.LENGTH_SHORT).show()
                        true
                    }

                    R.id.menu_month_year_title -> {
                        Toast.makeText(context, "Month/Year Title Clicked", Toast.LENGTH_SHORT)
                            .show()
                        true
                    }

                    R.id.menu_next_month -> {
                        Toast.makeText(context, "Next Month Clicked", Toast.LENGTH_SHORT).show()
                        true
                    }

                    R.id.menu_export_history -> {
                        Toast.makeText(context, "Export Clicked", Toast.LENGTH_SHORT).show()
                        true
                    }

                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun setupRecyclerView() {
        dashHistoryAdapter = DashHistoryAdapter()
        binding.dashHistoryRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = dashHistoryAdapter
        }
    }

    private fun observeViewModel() {
        viewModel.daySummaries.observe(viewLifecycleOwner) { daySummaries ->
            dashHistoryAdapter.submitList(daySummaries)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}