package cloud.trotter.dashbuddy.domain.evaluation

data class OfferEvaluation(
    val action: OfferAction,
    val score: Double,
    val qualityLevel: String,
    val recommendationText: String,
    val payAmount: Double,
    val distanceMiles: Double,
    val dollarsPerMile: Double,
    val dollarsPerHour: Double,
    val itemCount: Double,
    val merchantName: String
)