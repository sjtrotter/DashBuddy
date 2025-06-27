package cloud.trotter.dashbuddy.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.log.debug.DebugLogAdapter
import cloud.trotter.dashbuddy.data.log.debug.DebugLogViewModel
import cloud.trotter.dashbuddy.databinding.FragmentDebugLogBinding // Import the binding class
import cloud.trotter.dashbuddy.log.Level as LogLevel
import cloud.trotter.dashbuddy.log.Logger as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        setupLogLevelSelector()
        setupButtons()
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupLogLevelSelector() {
        val logLevels = LogLevel.entries.map { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, logLevels)
        binding.logLevelDropdownText.setAdapter(adapter)

        val currentLogLevel = DashBuddyApplication.getLogLevel().name
        binding.logLevelDropdownText.setText(currentLogLevel, false)

        binding.logLevelDropdownText.setOnItemClickListener { parent, _, position, _ ->
            val selectedLevelName = parent.getItemAtPosition(position) as String
            try {
                val selectedLevel = LogLevel.valueOf(selectedLevelName)
                Log.i(TAG, "User selected new log level: $selectedLevel")
                DashBuddyApplication.setLogLevel(selectedLevel)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Could not convert '$selectedLevelName' to LogLevel", e)
            }
        }
    }

    private fun setupButtons() {
        binding.buttonSaveLog.setOnClickListener {
            Log.i(TAG, "Save log button clicked.")
            saveLogToFile()
        }

        binding.buttonShareLog.setOnClickListener {
            Log.i(TAG, "Share log button clicked.")
            shareLog()
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
            debugLogAdapter.submitList(messages.map { it })
            if (messages.isNotEmpty()) {
                binding.debugLogRecyclerView.post {
                    binding.debugLogRecyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    private fun getFullLogAsString(): String {
        return viewModel.logMessages.value?.joinToString(separator = "\n") { it.message.toString() } ?: "No logs to share."
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
                Toast.makeText(context, "Log saved to Downloads folder:\n${file.name}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save log file", e)
                Toast.makeText(context, "Error: Could not save log file.", Toast.LENGTH_SHORT).show()
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
