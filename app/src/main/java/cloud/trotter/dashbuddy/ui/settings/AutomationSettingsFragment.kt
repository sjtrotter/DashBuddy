package cloud.trotter.dashbuddy.ui.fragments.settings

import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import cloud.trotter.dashbuddy.R

class AutomationSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.automation_preferences, rootKey)

        // Enforce Number Input for all decimal fields
        setupNumberInput("pref_aa_min_pay")
        setupNumberInput("pref_aa_min_ratio")
        setupNumberInput("pref_ad_max_pay")
    }

    private fun setupNumberInput(key: String) {
        val pref = findPreference<EditTextPreference>(key)
        pref?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
    }
}