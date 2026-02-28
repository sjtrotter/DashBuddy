package cloud.trotter.dashbuddy.data.vehicle.api.dto

import kotlinx.serialization.Serializable

/**
 * When we finally query the specific vehicle ID, it returns a massive JSON object.
 * We only care about the combined MPG right now.
 */
@Serializable
data class VehicleDetailsResponse(
    val comb08: String,
    val fuelType1: String
) {
    val combinedMpg: Float?
        get() = comb08.toFloatOrNull()
}