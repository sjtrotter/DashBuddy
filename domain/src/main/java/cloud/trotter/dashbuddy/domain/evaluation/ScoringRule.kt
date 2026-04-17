package cloud.trotter.dashbuddy.domain.evaluation

/**
 * The Base Rule. All rules (Money, Distance, Store-Specific) inherit from this.
 */
sealed class ScoringRule {
    abstract val id: String
    abstract val isEnabled: Boolean

    data class MetricRule(
        override val id: String,
        override val isEnabled: Boolean = true,
        val metricType: MetricType,
        val targetValue: Float,
        val autoDeclineOnFail: Boolean = false
    ) : ScoringRule()

    data class MerchantRule(
        override val id: String,
        override val isEnabled: Boolean = true,
        val storeName: String,
        val action: MerchantAction
    ) : ScoringRule()
}
