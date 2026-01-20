package cloud.trotter.dashbuddy.data

import androidx.preference.PreferenceManager
import cloud.trotter.dashbuddy.DashBuddyApplication

/**
 * Single source of truth for all User Settings.
 */
object Prefs {

    private val prefs by lazy {
        PreferenceManager.getDefaultSharedPreferences(DashBuddyApplication.context)
    }

    // --- KEYS ---
    // Must match app/src/main/res/xml/root_preferences.xml
    private const val KEY_SCORING_METRIC = "pref_scoring_metric"
    private const val KEY_MIN_PAY = "pref_min_pay_threshold"
    private const val KEY_AUTO_PILOT = "pref_master_auto_pilot"
    private const val KEY_SAFE_MODE = "pref_safe_mode"
    private const val KEY_AUTO_EXPAND = "pref_auto_expand"

    // --- ACCESSORS ---

    val scoringMetric: String
        get() = prefs.getString(KEY_SCORING_METRIC, "Payout") ?: "Payout"

    val minPayThreshold: Double
        get() = prefs.getInt(KEY_MIN_PAY, 5).toDouble()

    val isAutoPilotEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PILOT, false)

    val isSafeModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SAFE_MODE, true)

    val isAutoExpandEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_EXPAND, true)

    // --- Helper for updating legacy OfferEvaluator logic ---
    fun getScoringWeights(): Triple<Float, Float, Float> {
        return when (scoringMetric) {
            "DollarPerMile" -> Triple(0.2f, 0.3f, 0.2f)
            "DollarPerHour" -> Triple(0.2f, 0.2f, 0.3f)
            else -> Triple(0.3f, 0.2f, 0.2f) // Payout
        }
    }
}