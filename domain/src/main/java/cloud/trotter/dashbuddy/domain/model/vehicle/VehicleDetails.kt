package cloud.trotter.dashbuddy.domain.model.vehicle

/**
 * The resolved details for a specific vehicle, expressed entirely in domain types.
 * Replaces provider-specific response DTOs (e.g. EPA's VehicleDetailsResponse).
 *
 * @param combinedMpg Combined city/highway MPG, or null if unavailable.
 * @param fuelType    The primary fuel type, already mapped to the domain [FuelType] enum.
 */
data class VehicleDetails(
    val combinedMpg: Float?,
    val fuelType: FuelType
)
