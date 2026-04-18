package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleType

data class OfferEvaluation(
    val action: OfferAction,
    val score: Double,
    val qualityLevel: String,
    val recommendationText: String,
    /** Gross pay as shown on the offer screen. */
    val payAmount: Double,
    /** Estimated fuel cost for this offer's route. Zero for [VehicleType.E_BIKE]. */
    val fuelCostEstimate: Double,
    /** Net pay after fuel cost: [payAmount] - [fuelCostEstimate]. */
    val netPayAmount: Double,
    val distanceMiles: Double,
    /** Net dollars per mile ([netPayAmount] / [distanceMiles]). */
    val dollarsPerMile: Double,
    /** Net implied hourly rate. */
    val dollarsPerHour: Double,
    /** Estimated time for this offer in minutes, based on [UserEconomy] constants. */
    val estimatedTimeMinutes: Double,
    val itemCount: Double,
    val merchantName: String,
    /**
     * Caveat messages about unrealistic rule targets. Empty when all targets are reasonable.
     * Shown to the user when configuring rules so they understand why offers are being declined.
     */
    val warnings: List<String> = emptyList(),
)
