package cloud.trotter.dashbuddy.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
// Removed: import androidx.fragment.app.FragmentContainerView - not directly used as a class member
import cloud.trotter.dashbuddy.DashBuddyApplication // For accessing SharedPreferences
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.ui.fragments.DashLogFragment
import cloud.trotter.dashbuddy.ui.fragments.DebugLogFragment // Assuming you will create this
import cloud.trotter.dashbuddy.ui.fragments.OfferListFragment
import cloud.trotter.dashbuddy.ui.fragments.SettingsFragment
import cloud.trotter.dashbuddy.ui.fragments.StatisticsFragment
import cloud.trotter.dashbuddy.log.Logger as Log // Your Logger
import com.google.android.material.bottomnavigation.BottomNavigationView

// Define the interface for SettingsFragment to call back to Activity
interface DebugModeToggleListener {
    fun updateDebugTabVisibility()
}

class BubbleActivity : AppCompatActivity(), DebugModeToggleListener {

    private lateinit var bottomNav: BottomNavigationView
    // private lateinit var fragmentContainer: FragmentContainerView // Not strictly needed as a member variable

    companion object {
        const val EXTRA_TARGET_TAB_ID = "cloud.trotter.dashbuddy.TARGET_TAB_ID"
        private const val TAG = "BubbleActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.bubble_layout) // This layout contains your FragmentContainerView and BottomNavigationView

        bottomNav = findViewById(R.id.bubble_bottom_nav)
        // fragmentContainer = findViewById(R.id.bubble_fragment_container) // findViewById is fine in onCreate

        setupBottomNavigation()
        updateDebugTabVisibility() // Initial visibility check

        if (savedInstanceState == null) {
            // Check if an intent extra specifies a tab to open
            val targetTabId = intent.getIntExtra(EXTRA_TARGET_TAB_ID, R.id.nav_dash_log) // Default to Dash Log
            bottomNav.selectedItemId = targetTabId
            Log.d(TAG, "onCreate: Initial tab selected: ${getFragmentName(targetTabId)}")
        }
        // If savedInstanceState is not null, Android often restores the selected tab automatically.
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Handle new intent if the activity is already running (e.g., singleTop launchMode)
        // This is useful if the bubble is tapped again while BubbleActivity is open.
        setIntent(intent) // Update the activity's intent
        val targetTabId = intent?.getIntExtra(EXTRA_TARGET_TAB_ID, -1)
        if (targetTabId != -1 && targetTabId != null) {
            bottomNav.selectedItemId = targetTabId
            Log.d(TAG, "onNewIntent: Switched to tab: ${getFragmentName(targetTabId)}")
        }
    }


    override fun onResume() {
        super.onResume()
        // Update tab visibility in case settings changed while activity was paused
        updateDebugTabVisibility()
        Log.d(TAG, "onResume: Debug tab visibility updated.")
    }

    private fun setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            Log.d(TAG, "BottomNav item selected: ${item.title}")
            when (item.itemId) {
                R.id.nav_statistics -> {
                    loadFragment(StatisticsFragment(), "StatisticsFragment")
                    true
                }

                R.id.nav_offers -> {
                    loadFragment(OfferListFragment(), "OfferListFragment")
                    true
                }

                R.id.nav_dash_log -> {
                    loadFragment(DashLogFragment(), "DashLogFragment")
                    true
                }

                R.id.nav_debug_log -> { // Handle new debug log tab
                    if (item.isVisible) { // Only load if it's supposed to be visible
                        loadFragment(DebugLogFragment(), "DebugLogFragment")
                    } else {
                        // Optional: Prevent selection or navigate to a default if clicked while invisible
                        // (though it shouldn't be clickable if invisible)
                        Log.w(TAG, "Debug log tab clicked but should be invisible.")
                        return@setOnItemSelectedListener false // Prevent selection
                    }
                    true
                }

                R.id.nav_settings -> {
                    loadFragment(SettingsFragment(), "SettingsFragment")
                    true
                }

                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment, tag: String) {
        Log.d(TAG, "Loading fragment: $tag")
        supportFragmentManager.beginTransaction()
            .replace(R.id.bubble_fragment_container, fragment, tag)
            .commit()
    }

    // Implementation of the interface from SettingsFragment
    override fun updateDebugTabVisibility() {
        val isDebugModeEnabled = DashBuddyApplication.getDebugMode()
        val debugTabMenuItem = bottomNav.menu.findItem(R.id.nav_debug_log)

        if (debugTabMenuItem != null) {
            if (debugTabMenuItem.isVisible != isDebugModeEnabled) { // Only update if state changed
                debugTabMenuItem.isVisible = isDebugModeEnabled
                Log.i(TAG, "Debug tab visibility updated to: $isDebugModeEnabled")
                // If debug mode was just turned OFF and the debug tab was selected,
                // you might want to navigate to a default tab.
                if (!isDebugModeEnabled && bottomNav.selectedItemId == R.id.nav_debug_log) {
                    Log.d(
                        TAG,
                        "Debug mode turned off while debug tab was active. Switching to default."
                    )
                    bottomNav.selectedItemId = R.id.nav_dash_log // Or your preferred default
                }
            }
        } else {
            Log.w(TAG, "Could not find R.id.navigation_debug_log menu item to set visibility.")
        }
    }

    // Helper to get fragment name for logging (optional)
    private fun getFragmentName(itemId: Int): String {
        return when (itemId) {
            R.id.nav_statistics -> "Statistics"
            R.id.nav_offers -> "Offers"
            R.id.nav_dash_log -> "Dash Log"
            R.id.nav_debug_log -> "Debug Log"
            R.id.nav_settings -> "Settings"
            else -> "Unknown Tab"
        }
    }
}
