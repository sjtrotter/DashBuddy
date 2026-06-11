package cloud.trotter.dashbuddy.domain.evaluation

object ScoringUtils {

    // Threshold scores
    private const val DECENT = 40.0
    private const val GOOD = 50.0
    private const val GREAT = 60.0
    private const val AWESOME = 70.0


    fun determineOfferQuality(score: Double): String {
        return when {
            score >= AWESOME -> "AWESOME OFFER"
            score >= GREAT -> "GREAT OFFER"
            score >= GOOD -> "GOOD OFFER"
            score >= DECENT -> "DECENT OFFER"
            else -> "BAD OFFER"
        }
    }
}