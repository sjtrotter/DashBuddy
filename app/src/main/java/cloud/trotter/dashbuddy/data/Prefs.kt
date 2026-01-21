package cloud.trotter.dashbuddy.data

import androidx.preference.PreferenceManager
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.state.model.config.EvaluationConfig
import cloud.trotter.dashbuddy.state.model.config.OfferAutomationConfig
import cloud.trotter.dashbuddy.state.model.config.PostDeliveryConfig

object Prefs {
    private val prefs by lazy {
        PreferenceManager.getDefaultSharedPreferences(DashBuddyApplication.context)
    }

    // --- 1. EVALUATION (The Accountant) ---
    val evaluationConfig: EvaluationConfig
        get() = EvaluationConfig(
            prioritizedMetric = prefs.getString("pref_metric", "Payout") ?: "Payout",

            // Baselines
            maxExpectedPay = getDouble("pref_market_max_pay", 15.0),
            maxWillingDistance = getDouble("pref_market_max_dist", 12.0),
            targetHourlyRate = getDouble("pref_market_target_hourly", 25.0),
            maxItemTolerance = getDouble("pref_market_max_items", 15.0),
            strictShoppingMode = prefs.getBoolean("pref_shopping_penalty_enabled", true),

            // Weights (Stored as Int 0-100 in SeekBars, converted to Float 0.0-1.0)
            weightPay = getWeight("pref_weight_pay", 40),
            weightDistance = getWeight("pref_weight_distance", 30),
            weightTime = getWeight("pref_weight_time", 20),
            weightItemCount = getWeight("pref_weight_items", 10),

            multiStopPenalty = prefs.getInt("pref_weight_legs", 10).toFloat()
        )

    // --- 2. AUTOMATION (The Offer Robot) ---
    val offerAutomationConfig: OfferAutomationConfig
        get() = OfferAutomationConfig(
            masterAutoPilotEnabled = prefs.getBoolean("pref_master_auto_pilot", false),

            autoAcceptEnabled = prefs.getBoolean("pref_auto_accept_master", false),
            autoAcceptMinPay = getDouble("pref_aa_min_pay", 10.0),
            autoAcceptMinRatio = getDouble("pref_aa_min_ratio", 2.0),

            autoDeclineEnabled = prefs.getBoolean("pref_auto_decline_master", false),
            autoDeclineMaxPay = getDouble("pref_ad_max_pay", 3.50),
            autoDeclineMinRatio = getDouble("pref_ad_min_ratio", 0.50)
        )

    // --- 3. POST-DELIVERY (The Assistant) ---
    val postDeliveryConfig: PostDeliveryConfig
        get() = PostDeliveryConfig(
            masterAutoPilotEnabled = prefs.getBoolean("pref_master_auto_pilot", false),
            autoExpandDetails = prefs.getBoolean("pref_auto_expand", true)
        )

    // --- Helpers ---
    private fun getDouble(key: String, default: Double): Double {
        return try {
            prefs.getString(key, default.toString())?.toDoubleOrNull() ?: default
        } catch (e: Exception) {
            default
        }
    }

    private fun getWeight(key: String, default: Int): Float {
        return prefs.getInt(key, default) / 100f
    }
}