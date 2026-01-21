package cloud.trotter.dashbuddy.ui.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import cloud.trotter.dashbuddy.R

/**
 * Root Settings Fragment.
 * Handles the "Root" XML and navigation to sub-screens.
 */
class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }
}