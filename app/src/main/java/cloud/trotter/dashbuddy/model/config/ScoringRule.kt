package cloud.trotter.dashbuddy.model.config

import kotlinx.serialization.Serializable

/**
 * The Base Rule. All rules (Money, Distance, Store-Specific) inherit from this.
 */
@Serializable
sealed class ScoringRule {
    abstract val id: String
    abstract val isEnabled: Boolean

    @Serializable
    data class MetricRule(
        override val id: String,
        override val isEnabled: Boolean = true, // TRUE = Show math on HUD
        val metricType: MetricType,
        val targetValue: Float,
        val autoDeclineOnFail: Boolean = false // NEW: TRUE = Click the decline button for me
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