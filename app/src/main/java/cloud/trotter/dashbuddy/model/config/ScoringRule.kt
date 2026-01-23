package cloud.trotter.dashbuddy.model.config

import kotlinx.serialization.Serializable

/**
 * The Base Rule. All rules (Money, Distance, Store-Specific) inherit from this.
 */
@Serializable
sealed class ScoringRule {
    abstract val id: String
    abstract val isEnabled: Boolean

    // 1. The Standard Metric Rule (Pay, Distance, etc.)
    @Serializable
    data class MetricRule(
        override val id: String, // e.g., "rule_pay_target"
        override val isEnabled: Boolean = true,
        val metricType: MetricType,    // Enum: PAYOUT, DOLLAR_PER_MILE, ACTIVE_HOURLY
        val targetValue: Float   // The Slider Value (e.g., 15.0)
    ) : ScoringRule()

    // 2. The Store Rule (Future Proofing!)
    @Serializable
    data class MerchantRule(
        override val id: String, // e.g., "rule_ban_walmart"
        override val isEnabled: Boolean = true,
        val storeName: String,
        val action: MerchantAction // Enum: BAN, BOOST, PENALIZE
    ) : ScoringRule()
}