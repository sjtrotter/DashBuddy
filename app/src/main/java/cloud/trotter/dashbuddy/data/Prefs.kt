package cloud.trotter.dashbuddy.data

import androidx.core.content.edit
import androidx.preference.PreferenceManager
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.state.model.config.EvaluationConfig
import cloud.trotter.dashbuddy.state.model.config.OfferAutomationConfig
import cloud.trotter.dashbuddy.state.model.config.PostDeliveryConfig

object Prefs {
    private val prefs by lazy {
        PreferenceManager.getDefaultSharedPreferences(DashBuddyApplication.context)
    }

    var isFirstRun: Boolean
        get() = prefs.getBoolean("is_first_run", true)
        set(value) = prefs.edit { putBoolean("is_first_run", value) }

    // --- 1. EVALUATION (The Accountant) ---
    val evaluationConfig: EvaluationConfig
        get() = EvaluationConfig(
            prioritizedMetric = prefs.getString("pref_metric", "Payout") ?: "Payout",

            // UPDATED: Read these as Ints (from SeekBars) and convert to Double
            maxExpectedPay = getIntAsDouble("pref_market_max_pay", 15),
            maxWillingDistance = getIntAsDouble("pref_market_max_dist", 12),
            targetHourlyRate = getIntAsDouble("pref_market_target_hourly", 25),

            // This might still be an EditText or SeekBar depending on your XML.
            // If you changed it to SeekBar, use getIntAsDouble. If it's Text, use getDouble.
            // Based on your last request, let's assume standard SeekBars for safety:
            maxItemTolerance = getIntAsDouble("pref_market_max_items", 15),

            strictShoppingMode = prefs.getBoolean("pref_shopping_penalty_enabled", true),

            // Weights (0-100)
            weightPay = getWeight("pref_weight_pay", 40),
            weightDistance = getWeight("pref_weight_distance", 30),
            weightTime = getWeight("pref_weight_time", 20),
            weightItemCount = getWeight("pref_weight_items", 10),

            multiStopPenalty = prefs.getInt("pref_weight_legs", 10).toFloat()
        )

    // --- 2. AUTOMATION ---
    val offerAutomationConfig: OfferAutomationConfig
        get() = OfferAutomationConfig(
            masterAutoPilotEnabled = prefs.getBoolean("pref_master_auto_pilot", false),

            autoAcceptEnabled = prefs.getBoolean("pref_auto_accept_master", false),
            // These are likely still EditTexts (User types specific cents)
            autoAcceptMinPay = getDouble("pref_aa_min_pay", 10.0),
            autoAcceptMinRatio = getDouble("pref_aa_min_ratio", 2.0),

            autoDeclineEnabled = prefs.getBoolean("pref_auto_decline_master", false),
            autoDeclineMaxPay = getDouble("pref_ad_max_pay", 3.50),
            autoDeclineMinRatio = getDouble("pref_ad_min_ratio", 0.50)
        )

    // --- 3. POST-DELIVERY ---
    val postDeliveryConfig: PostDeliveryConfig
        get() = PostDeliveryConfig(
            masterAutoPilotEnabled = prefs.getBoolean("pref_master_auto_pilot", false),
            autoExpandDetails = prefs.getBoolean("pref_auto_expand", true)
        )

    // --- Helpers ---

    // For EditTextPreferences (Strings like "15.50")
    private fun getDouble(key: String, default: Double): Double {
        return try {
            prefs.getString(key, default.toString())?.toDoubleOrNull() ?: default
        } catch (e: Exception) {
            default
        }
    }

    // NEW: For SeekBarPreferences (Ints like 15)
    private fun getIntAsDouble(key: String, default: Int): Double {
        return try {
            prefs.getInt(key, default).toDouble()
        } catch (e: Exception) {
            // Fallback: If old string data still exists, try to parse it
            try {
                prefs.getString(key, default.toString())?.toDoubleOrNull() ?: default.toDouble()
            } catch (e2: Exception) {
                default.toDouble()
            }
        }
    }

    private fun getWeight(key: String, default: Int): Float {
        return prefs.getInt(key, default) / 100f
    }
}