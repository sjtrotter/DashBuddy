package cloud.trotter.dashbuddy.state.model.config

/**
 * Pure strategy settings. Used by OfferEvaluator.
 * Defines "What is a good offer?" based on User Market Settings.
 */
data class EvaluationConfig(
    val prioritizedMetric: String = "Payout", // "Payout", "DollarPerMile", "DollarPerHour"

    // --- Market Baselines (The "Unicorn" Scale) ---
    // The user defines their "100%" ceiling.
    val maxExpectedPay: Double = 15.0,
    val maxWillingDistance: Double = 12.0,
    val targetHourlyRate: Double = 25.0,

    // --- Workload & Tolerance ---
    val maxItemTolerance: Double = 15.0, // Order size where score drops to 0
    val strictShoppingMode: Boolean = true, // Penalize shopping orders more heavily

    // --- Weights (Strategy 0.0 - 1.0) ---
    val weightPay: Float = 0.4f,
    val weightDistance: Float = 0.3f,
    val weightTime: Float = 0.2f,
    val weightItemCount: Float = 0.1f,

    // --- Penalties ---
    val multiStopPenalty: Float = 10.0f // Points deducted per extra delivery leg
)