package cloud.trotter.dashbuddy.ui.fragments

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import androidx.fragment.app.Fragment
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.databinding.FragmentSettingsBinding
import cloud.trotter.dashbuddy.ui.activities.BubbleActivity
import cloud.trotter.dashbuddy.ui.interfaces.DebugModeToggleListener
import cloud.trotter.dashbuddy.log.Level as LogLevel
import cloud.trotter.dashbuddy.log.Logger as Log

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

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
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDebugSwitch()
        setupMenu()
    }

    private fun setupMenu() {
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Settings"

        // Set the navigation icon for the toolbar
        val toolbar =
            (activity as? BubbleActivity)?.findViewById<androidx.appcompat.widget.Toolbar>(R.id.bubble_toolbar)
        toolbar?.navigationIcon =
            getDrawable(DashBuddyApplication.context, R.drawable.ic_menu_toolbar_settings)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun setupDebugSwitch() {
        val isCurrentlyInDebugMode = DashBuddyApplication.getDebugMode()
        binding.switchDebugMode.isChecked = isCurrentlyInDebugMode
        Log.d(TAG, "Initial Debug Mode switch state: $isCurrentlyInDebugMode")

        binding.switchDebugMode.setOnCheckedChangeListener { _, isChecked ->
            Log.i(TAG, "Debug Mode switch toggled to: $isChecked")
            DashBuddyApplication.setDebugMode(isChecked)

            if (isChecked) {
                DashBuddyApplication.setLogLevel(LogLevel.VERBOSE)
                Log.i(TAG, "Debug Mode ON: Log level set to VERBOSE by default.")
                DashBuddyApplication.sendBubbleMessage("Debug Mode ON\nLogs: VERBOSE")
            } else {
                DashBuddyApplication.setLogLevel(LogLevel.INFO)
                Log.i(TAG, "Debug Mode OFF: Log level set to INFO.")
                DashBuddyApplication.sendBubbleMessage("Debug Mode OFF\nLogs: INFO")
            }

            listener?.updateDebugTabVisibility()
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}