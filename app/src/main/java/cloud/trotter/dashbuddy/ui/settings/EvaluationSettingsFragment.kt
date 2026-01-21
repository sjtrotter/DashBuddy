package cloud.trotter.dashbuddy.ui.fragments.settings

import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import cloud.trotter.dashbuddy.R

class EvaluationSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.evaluation_preferences, rootKey)

        // Enforce Number Input
        setupNumberInput("pref_market_max_pay")
        setupNumberInput("pref_market_max_dist")
        setupNumberInput("pref_market_target_hourly")
        setupNumberInput("pref_market_max_items")
    }

    private fun setupNumberInput(key: String) {
        val pref = findPreference<EditTextPreference>(key)
        pref?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
    }
}