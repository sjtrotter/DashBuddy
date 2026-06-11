package cloud.trotter.dashbuddy.domain.provider

import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleDetails
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleOption

/**
 * The standard contract for fetching vehicle efficiency data.
 * Any future provider (EPA, NHTSA, CarQuery) must implement this interface.
 * All return types are pure :domain models — no network DTOs leak upstream.
 *
 * Result-typed (#366), matching [FuelPriceDataSource] — one provider error
 * idiom; callers decide how to degrade.
 */
interface VehicleEfficiencyDataSource {
    suspend fun getYears(): Result<List<String>>
    suspend fun getMakes(year: String): Result<List<String>>
    suspend fun getModels(year: String, make: String): Result<List<String>>
    suspend fun getVehicleOptions(year: String, make: String, model: String): Result<List<VehicleOption>>
    suspend fun getVehicleDetails(vehicleId: String): Result<VehicleDetails>
}
