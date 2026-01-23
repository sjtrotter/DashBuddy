package cloud.trotter.dashbuddy.model.config

import kotlinx.serialization.Serializable

/**
 * The Snapshot of all rules needed to score an offer.
 */
@Serializable
data class EvaluationConfig(
    // 1. The "Platinum Mode" Override
    val protectStatsMode: Boolean = false,

    // 2. The Rack and Stack (Ordered List)
    // The evaluator will iterate through this list to apply weights.
    val rules: List<ScoringRule> = emptyList(),

    // 3. Simple Gates
    val allowShopping: Boolean = true
)