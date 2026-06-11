package cloud.trotter.dashbuddy.domain.evaluation

object ScoringUtils {

    // Threshold scores. AWESOME deliberately coincides with the ACCEPT
    // boundary — referencing the evaluator's constant keeps the action
    // decision and the top quality tier in lockstep (#400).
    private const val DECENT = 40.0
    private const val GOOD = 50.0
    private const val GREAT = 60.0
    private const val AWESOME = OfferEvaluator.ACCEPT_THRESHOLD


    fun determineOfferQuality(score: Double): OfferQuality {
        return when {
            score >= AWESOME -> OfferQuality.AWESOME
            score >= GREAT -> OfferQuality.GREAT
            score >= GOOD -> OfferQuality.GOOD
            score >= DECENT -> OfferQuality.DECENT
            else -> OfferQuality.BAD
        }
    }
}