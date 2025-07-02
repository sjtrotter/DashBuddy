package cloud.trotter.dashbuddy.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.databinding.BubbleLayoutBinding // CORRECTED: Import the binding class that matches bubble_layout.xml
import cloud.trotter.dashbuddy.ui.fragments.DashLogFragment
import cloud.trotter.dashbuddy.ui.fragments.DebugLogFragment
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.DashHistoryFragment
import cloud.trotter.dashbuddy.ui.fragments.SettingsFragment
import cloud.trotter.dashbuddy.ui.fragments.StatisticsFragment
import cloud.trotter.dashbuddy.log.Logger as Log

class BubbleActivity : AppCompatActivity(), DebugModeToggleListener {

    // CORRECTED: Use the binding class that matches your layout file's name
    private lateinit var binding: BubbleLayoutBinding

    companion object {
        const val EXTRA_TARGET_TAB_ID = "cloud.trotter.dashbuddy.TARGET_TAB_ID"
        private const val TAG = "BubbleActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // CORRECTED: Inflate the layout using the correct binding class
        binding = BubbleLayoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNavigation()
        updateDebugTabVisibility() // Initial visibility check

        if (savedInstanceState == null) {
            val targetTabId = intent.getIntExtra(EXTRA_TARGET_TAB_ID, R.id.nav_dash_log)
            binding.bubbleBottomNav.selectedItemId = targetTabId
            Log.d(TAG, "onCreate: Initial tab selected: ${getFragmentName(targetTabId)}")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val targetTabId = intent?.getIntExtra(EXTRA_TARGET_TAB_ID, -1)
        if (targetTabId != -1 && targetTabId != null) {
            binding.bubbleBottomNav.selectedItemId = targetTabId
            Log.d(TAG, "onNewIntent: Switched to tab: ${getFragmentName(targetTabId)}")
        }
    }

    override fun onResume() {
        super.onResume()
        updateDebugTabVisibility()
        Log.d(TAG, "onResume: Debug tab visibility updated.")
    }

    private fun setupBottomNavigation() {
        // Access the BottomNavigationView through the binding object
        binding.bubbleBottomNav.setOnItemSelectedListener { item ->
            Log.d(TAG, "BottomNav item selected: ${item.title}")
            when (item.itemId) {
                R.id.nav_statistics -> loadFragment(StatisticsFragment(), "StatisticsFragment")
                R.id.nav_dash_history -> loadFragment(DashHistoryFragment(), "DashHistoryFragment")
                R.id.nav_dash_log -> loadFragment(DashLogFragment(), "DashLogFragment")
                R.id.nav_debug_log -> {
                    if (item.isVisible) {
                        loadFragment(DebugLogFragment(), "DebugLogFragment")
                    } else {
                        Log.w(TAG, "Debug log tab clicked but should be invisible.")
                        return@setOnItemSelectedListener false
                    }
                }

                R.id.nav_settings -> loadFragment(SettingsFragment(), "SettingsFragment")
                else -> return@setOnItemSelectedListener false
            }
            true
        }
    }

    private fun loadFragment(fragment: Fragment, tag: String) {
        Log.d(TAG, "Loading fragment: $tag")
        // Access the fragment container through the binding object
        supportFragmentManager.beginTransaction()
            .replace(binding.bubbleFragmentContainer.id, fragment, tag)
            .commit()
    }

    override fun updateDebugTabVisibility() {
        val isDebugModeEnabled = DashBuddyApplication.getDebugMode()
        val debugTabMenuItem = binding.bubbleBottomNav.menu.findItem(R.id.nav_debug_log)

        if (debugTabMenuItem != null) {
            if (debugTabMenuItem.isVisible != isDebugModeEnabled) {
                debugTabMenuItem.isVisible = isDebugModeEnabled
                Log.i(TAG, "Debug tab visibility updated to: $isDebugModeEnabled")
                if (!isDebugModeEnabled && binding.bubbleBottomNav.selectedItemId == R.id.nav_debug_log) {
                    Log.d(
                        TAG,
                        "Debug mode turned off while debug tab was active. Switching to default."
                    )
                    binding.bubbleBottomNav.selectedItemId = R.id.nav_dash_log
                }
            }
        } else {
            Log.w(TAG, "Could not find R.id.navigation_debug_log menu item to set visibility.")
        }
    }

    private fun getFragmentName(itemId: Int): String {
        return when (itemId) {
            R.id.nav_statistics -> "Statistics"
            R.id.nav_dash_history -> "Offers"
            R.id.nav_dash_log -> "Dash Log"
            R.id.nav_debug_log -> "Debug Log"
            R.id.nav_settings -> "Settings"
            else -> "Unknown Tab"
        }
    }
}
