package cloud.trotter.dashbuddy.domain.config

/**
 * The Snapshot of all rules needed to score an offer.
 */
data class EvaluationConfig(
    val protectStatsMode: Boolean = false,
    val rules: List<ScoringRule> = emptyList(),
    val allowShopping: Boolean = true
)