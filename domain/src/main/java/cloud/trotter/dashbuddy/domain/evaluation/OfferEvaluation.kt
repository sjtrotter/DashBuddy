package cloud.trotter.dashbuddy.domain.evaluation

import kotlinx.serialization.Serializable

@Serializable

data class OfferEvaluation(
    val action: OfferAction,
    val score: Double,
    val qualityLevel: OfferQuality,
    /** Gross pay as shown on the offer screen. */
    val payAmount: Double,
    /** Estimated fuel cost for this offer's route. Zero for non-fuel vehicle classes. */
    val fuelCostEstimate: Double,
    /**
     * Estimated non-fuel operating cost for this offer's route:
     * tires + oil + brakes + fluids + misc + depreciation + insurance +
     * registration + phone. Amortized per-mile.
     */
    val nonFuelCostEstimate: Double = 0.0,
    /** [fuelCostEstimate] + [nonFuelCostEstimate]. */
    val totalOperatingCost: Double = 0.0,
    /** Total operating cost per mile from the user's economy profile. */
    val operatingCostPerMile: Double = 0.0,
    /** Net pay after all operating costs: [payAmount] - [totalOperatingCost]. */
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
     * True when the user's [UserEconomy] still has at least one field at its
     * vehicle-class / built-in default. Surfaces a "(default)" hint in the UI.
     */
    val isUsingDefaults: Boolean = false,
    /**
     * Caveat messages about unrealistic rule targets. Empty when all targets are reasonable.
     * Shown to the user when configuring rules so they understand why offers are being declined.
     */
    val warnings: List<String> = emptyList(),
)
