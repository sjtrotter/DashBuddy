package cloud.trotter.dashbuddy.domain.evaluation

enum class MerchantAction {
    /** Hard decline before metric scoring — offer is rejected immediately. */
    BLOCK,

    /** Passes through scoring but always surfaces for human review regardless of score. */
    MANUAL_REVIEW,

    /** Multiplies the final score by [ScoringRule.MerchantRule.scoreModifier]. Positive = boost, negative = penalty. */
    SCORE_MODIFIER,
}
