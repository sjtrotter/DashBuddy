package cloud.trotter.dashbuddy.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.databinding.ActivityBubbleBinding
import cloud.trotter.dashbuddy.log.Logger as Log

class BubbleActivity : AppCompatActivity(), DebugModeToggleListener {

    private lateinit var binding: ActivityBubbleBinding

    companion object {
        const val EXTRA_TARGET_TAB_ID = "cloud.trotter.dashbuddy.TARGET_TAB_ID"
        private const val TAG = "BubbleActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBubbleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // --- New Setup Logic ---
        // 1. Set our custom Toolbar as the activity's official ActionBar
        setSupportActionBar(binding.bubbleToolbar)

        // 2. Get the NavController from the NavHostFragment
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // 3. Connect the BottomNavigationView to the NavController.
        // This automatically handles fragment transactions when tabs are clicked.
        binding.bubbleBottomNav.setupWithNavController(navController)
        // --- End New Setup Logic ---

        updateDebugTabVisibility() // Initial visibility check

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateDebugTabVisibility()
        Log.d(TAG, "onResume: Debug tab visibility updated.")
    }

    // A single helper to handle navigation from intents
    private fun handleIntent(intent: Intent?) {
        val targetTabId = intent?.getIntExtra(EXTRA_TARGET_TAB_ID, -1) ?: -1
        if (targetTabId != -1) {
            binding.bubbleBottomNav.selectedItemId = targetTabId
            Log.d(TAG, "Navigated to tab: ${getFragmentName(targetTabId)}")
        }
    }

    // The manual setupBottomNavigation and loadFragment methods are now removed.

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
            Log.w(TAG, "Could not find R.id.nav_debug_log menu item to set visibility.")
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