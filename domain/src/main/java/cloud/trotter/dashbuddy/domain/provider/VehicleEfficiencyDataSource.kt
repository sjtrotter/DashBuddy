package cloud.trotter.dashbuddy.domain.provider

import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleDetails
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleOption

/**
 * The standard contract for fetching vehicle efficiency data.
 * Any future provider (EPA, NHTSA, CarQuery) must implement this interface.
 * All return types are pure :domain models — no network DTOs leak upstream.
 */
interface VehicleEfficiencyDataSource {
    suspend fun getYears(): List<String>
    suspend fun getMakes(year: String): List<String>
    suspend fun getModels(year: String, make: String): List<String>
    suspend fun getVehicleOptions(year: String, make: String, model: String): List<VehicleOption>
    suspend fun getVehicleDetails(vehicleId: String): VehicleDetails?
}
