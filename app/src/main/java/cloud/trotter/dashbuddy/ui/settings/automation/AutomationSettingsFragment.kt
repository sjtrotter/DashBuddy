package cloud.trotter.dashbuddy.ui.settings.automation

import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.util.ViewUtils

class AutomationSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.automation_preferences, rootKey)

        // Setup inputs with dollar signs
        setupInput("pref_aa_min_pay", "$")
        setupInput("pref_aa_min_ratio", "$/mi ")
        setupInput("pref_ad_max_pay", "$")
    }

    private fun setupInput(key: String, prefix: String = "", suffix: String = "") {
        val pref = findPreference<EditTextPreference>(key) ?: return

        // 1. Dynamic Summary
        pref.summaryProvider =
            Preference.SummaryProvider<EditTextPreference> { preference ->
                val text = preference.text
                if (text.isNullOrEmpty()) "Not set" else "$prefix$text$suffix"
            }

        // 2. Units in the Box!
        pref.setOnBindEditTextListener { editText ->
            editText.inputType =
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

            // Generate the unit icons
            val prefixDrawable = if (prefix.isNotEmpty()) ViewUtils.createTextDrawable(
                requireContext(),
                prefix
            ) else null
            val suffixDrawable = if (suffix.isNotEmpty()) ViewUtils.createTextDrawable(
                requireContext(),
                suffix
            ) else null

            // Set them as "Compound Drawables" (Icons inside the text box)
            editText.setCompoundDrawablesWithIntrinsicBounds(
                prefixDrawable,
                null,
                suffixDrawable,
                null
            )

            // Add a little padding so text doesn't overlap the icon
            editText.compoundDrawablePadding = 16
        }
    }
}