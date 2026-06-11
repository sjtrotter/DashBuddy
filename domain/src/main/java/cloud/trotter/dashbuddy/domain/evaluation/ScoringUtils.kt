package cloud.trotter.dashbuddy.domain.evaluation

object ScoringUtils {

    // Threshold scores
    private const val DECENT = 40.0
    private const val GOOD = 50.0
    private const val GREAT = 60.0
    private const val AWESOME = 70.0


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