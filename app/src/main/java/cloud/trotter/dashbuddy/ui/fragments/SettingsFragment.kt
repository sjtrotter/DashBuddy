package cloud.trotter.dashbuddy.ui.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.ui.activities.DebugModeToggleListener
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.log.Level as LogLevel
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsFragment : Fragment() {

    private lateinit var debugModeSwitch: MaterialSwitch
    private var listener: DebugModeToggleListener? = null

    companion object {
        private const val TAG = "SettingsFragment"
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is DebugModeToggleListener) {
            listener = context
        } else {
            Log.w(TAG, "$context must implement DebugModeToggleListener")
            // throw RuntimeException("$context must implement DebugModeToggleListener") // Or handle gracefully
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        debugModeSwitch = view.findViewById(R.id.switch_debug_mode)
        setupDebugSwitch()
    }

    private fun setupDebugSwitch() {
        // Load the initial state of the debug mode switch
        val isCurrentlyInDebugMode = DashBuddyApplication.getDebugMode()
        debugModeSwitch.isChecked = isCurrentlyInDebugMode
        Log.d(TAG, "Initial Debug Mode switch state: $isCurrentlyInDebugMode")

        // Listener for the debug mode switch
        debugModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.i(TAG, "Debug Mode switch toggled to: $isChecked")

            // 1. Update the global debug mode preference (for tab visibility, etc.)
            DashBuddyApplication.setDebugMode(isChecked)

            // 2. Set a default log level based on the debug mode state
            if (isChecked) {
                // When debug mode is turned ON, set log level to DEBUG (or VERBOSE)
                DashBuddyApplication.setLogLevel(LogLevel.VERBOSE) // Or LogLevel.DEBUG
                Log.i(TAG, "Debug Mode ON: Log level set to VERBOSE by default.")
                DashBuddyApplication.sendBubbleMessage("Debug Mode ON\nLogs: VERBOSE")
            } else {
                // When debug mode is turned OFF, set log level to a production default (e.g., INFO)
                DashBuddyApplication.setLogLevel(LogLevel.INFO)
                Log.i(TAG, "Debug Mode OFF: Log level set to INFO.")
                DashBuddyApplication.sendBubbleMessage("Debug Mode OFF\nLogs: INFO")
            }

            // 3. Notify the Activity to update UI (e.g., show/hide debug tab)
            // This part remains crucial and needs to be handled in your BubbleActivity
            // by reading DashBuddyApplication.getDebugMode() when appropriate (e.g., onResume,
            // or via a more direct communication like an event or interface).
            // For example:
            // (activity as? YourActivityInterface)?.onDebugModeToggled(isChecked)
            Log.d(TAG, "Signaling Activity (conceptually) to update UI for debug mode: $isChecked")
        }
    }
}
