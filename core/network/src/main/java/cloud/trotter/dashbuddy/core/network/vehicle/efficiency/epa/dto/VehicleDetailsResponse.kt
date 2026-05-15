package cloud.trotter.dashbuddy.core.network.vehicle.efficiency.epa.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of the EPA fueleconomy.gov per-vehicle response. The full payload is large;
 * we extract only the fields DashBuddy uses today: combined MPG, fuel type, and the
 * EPA vehicle class (used to pre-select a VehicleClass cost profile in the wizard).
 */
@Serializable
data class VehicleDetailsResponse(
    val comb08: String,
    val fuelType1: String,
    /** EPA size class, e.g. "Compact Cars", "Midsize Cars", "Standard Sport Utility Vehicle". */
    @SerialName("VClass") val vClass: String? = null,
) {
    val combinedMpg: Float?
        get() = comb08.toFloatOrNull()
}