package cloud.trotter.dashbuddy.util

object ScoringUtils {

    // Threshold scores
    private const val DECENT = 40.0
    private const val GOOD = 50.0
    private const val GREAT = 60.0
    private const val AWESOME = 70.0

    fun calculateNormalizedScore(value: Double, min: Double, max: Double, weight: Float): Double {
        val normalizedValue = (value - min) / (max - min)
        val clampedValue = minOf(1.0, maxOf(0.0, normalizedValue))
        return clampedValue * weight
    }

    fun calculateInvertedNormalizedScore(
        value: Double,
        min: Double,
        max: Double,
        weight: Float
    ): Double {
        val normalizedValue = (max - value) / (max - min)
        val clampedValue = minOf(1.0, maxOf(0.0, normalizedValue))
        return clampedValue * weight
    }

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
