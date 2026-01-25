package cloud.trotter.dashbuddy.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.data.log.debug.DebugLogAdapter
import cloud.trotter.dashbuddy.data.log.debug.DebugLogViewModel
import cloud.trotter.dashbuddy.databinding.FragmentDebugLogBinding
import cloud.trotter.dashbuddy.ui.bubble.BubbleActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import cloud.trotter.dashbuddy.log.Level as LogLevel
import cloud.trotter.dashbuddy.log.Logger as Log

class DebugLogFragment : Fragment() {

    private var _binding: FragmentDebugLogBinding? = null
    private val binding get() = _binding!!

    private lateinit var debugLogAdapter: DebugLogAdapter
    private val viewModel: DebugLogViewModel by viewModels()

    companion object {
        private const val TAG = "DebugLogFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDebugLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Your existing setup calls
        setupRecyclerView()
        observeViewModel()

        // --- NEW: Setup the MenuProvider ---
        setupMenu()
    }

    // This is the new, recommended way to handle fragment-specific menus
    private fun setupMenu() {
        // Set title
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Debug"

        // Set the navigation icon for the toolbar
        val toolbar =
            (activity as? BubbleActivity)?.findViewById<androidx.appcompat.widget.Toolbar>(R.id.bubble_toolbar)
        toolbar?.navigationIcon =
            getDrawable(DashBuddyApplication.context, R.drawable.ic_menu_toolbar_debug)

        // The MenuHost is typically the Activity
        val menuHost = requireActivity()

        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                // Inflate the menu resource into the toolbar's menu
                menuInflater.inflate(R.menu.debug_log_menu, menu)

                // Find the spinner within the inflated menu and set it up
                val logLevelItem = menu.findItem(R.id.menu_log_level)
                val spinner = logLevelItem.actionView?.findViewById<Spinner>(R.id.spinner_log_level)
                setupLogLevelSelector(spinner)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                // Handle clicks on the action icons
                return when (menuItem.itemId) {
                    R.id.menu_save_log -> {
                        Log.i(TAG, "Save log button clicked.")
                        saveLogToFile()
                        true // Signify that the event was handled
                    }

                    R.id.menu_share_log -> {
                        Log.i(TAG, "Share log button clicked.")
                        shareLog()
                        true // Signify that the event was handled
                    }

                    else -> false // Let the system handle other menu items
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED) // Tying it to the view's lifecycle
    }

    // The old setHasOptionsMenu, onCreateOptionsMenu, and onOptionsItemSelected are now removed.

    private fun setupLogLevelSelector(spinner: Spinner?) {
        spinner ?: return // Guard against the spinner being null
        val logLevels = LogLevel.entries.map { it.name }
        val adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, logLevels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        spinner.adapter = adapter

        val currentLogLevelName = DashBuddyApplication.getLogLevel().name
        spinner.setSelection(logLevels.indexOf(currentLogLevelName))

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selectedLevelName = parent?.getItemAtPosition(position) as String
                try {
                    val selectedLevel = LogLevel.valueOf(selectedLevelName)
                    Log.i(TAG, "User selected new log level: $selectedLevel")
                    DashBuddyApplication.setLogLevel(selectedLevel)
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Could not convert '$selectedLevelName' to LogLevel", e)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupRecyclerView() {
        debugLogAdapter = DebugLogAdapter()
        binding.debugLogRecyclerView.layoutManager = LinearLayoutManager(context).apply {
            stackFromEnd = true
        }
        binding.debugLogRecyclerView.adapter = debugLogAdapter
    }

    private fun observeViewModel() {
        viewModel.logMessages.observe(viewLifecycleOwner) { messages ->
            debugLogAdapter.submitList(messages)
            if (messages.isNotEmpty()) {
                binding.debugLogRecyclerView.post {
                    binding.debugLogRecyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    // ... (Your other helper functions: getFullLogAsString, shareLog, saveLogToFile, etc.)
    private fun getFullLogAsString(): String {
        return viewModel.logMessages.value?.joinToString(separator = "\n") { it.message.toString() }
            ?: "No logs to share."
    }

    private fun shareLog() {
        val logText = getFullLogAsString()
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, logText)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(shareIntent, "Share DashBuddy Debug Log"))
    }

    private fun saveLogToFile() {
        val logText = getFullLogAsString()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = writeLogToTempFile(logText)
                Toast.makeText(
                    context,
                    "Log saved to Downloads folder:\n${file.name}",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save log file", e)
                Toast.makeText(context, "Error: Could not save log file.", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private suspend fun writeLogToTempFile(logContent: String): File {
        return withContext(Dispatchers.IO) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputDir = requireContext().cacheDir
            val outputFile = File(outputDir, "DashBuddy_DebugLog_$timestamp.txt")
            outputFile.writeText(logContent)
            outputFile
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}