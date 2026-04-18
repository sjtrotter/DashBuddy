package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleType

/**
 * The persistent financial and time-estimation profile of the Dasher.
 * Assembled from stored preferences and passed into [EvaluationConfig].
 *
 * Fuel cost is computed from [gasPricePerGallon] / [vehicleMpg]. E-bikes carry no fuel cost.
 * Time estimation defaults ([avgMinutesPerMile], [basePickupMinutes]) are user-tunable to
 * account for market conditions (rural vs. dense city, typical restaurant wait times).
 */
data class UserEconomy(
    val vehicleType: VehicleType = VehicleType.CAR,
    val vehicleMpg: Double = 30.0,
    val gasPricePerGallon: Double = 3.50,
    /** Minutes per mile of driving — default 2.5 (urban average). */
    val avgMinutesPerMile: Double = DEFAULT_MINUTES_PER_MILE,
    /** Base overhead per offer (pickup + dropoff) in minutes — default 7.0. */
    val basePickupMinutes: Double = DEFAULT_BASE_PICKUP_MINUTES,
) {
    /**
     * Marginal fuel cost per mile. Zero for [VehicleType.E_BIKE].
     * EV car cost modeling is future work — modeled as zero until then.
     */
    val fuelCostPerMile: Double
        get() = when (vehicleType) {
            VehicleType.E_BIKE -> 0.0
            VehicleType.CAR -> if (vehicleMpg > 0) gasPricePerGallon / vehicleMpg else 0.0
        }

    companion object {
        const val DEFAULT_MINUTES_PER_MILE = 2.5
        const val DEFAULT_BASE_PICKUP_MINUTES = 7.0
    }
}
