package cloud.trotter.dashbuddy.ui.settings

import android.os.Bundle
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import cloud.trotter.dashbuddy.R

class SettingsFragment : PreferenceFragmentCompat(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        when (pref.key) {
            "screen_evaluation" -> {
                findNavController().navigate(R.id.action_settings_to_market)
                return true
            }

            "screen_automation" -> {
                findNavController().navigate(R.id.action_settings_to_automation)
                return true
            }
        }
        return false
    }
}