package cloud.trotter.dashbuddy.data.vehicle

import cloud.trotter.dashbuddy.data.vehicle.api.FuelEconomyApi
import cloud.trotter.dashbuddy.data.vehicle.api.dto.MenuItem
import cloud.trotter.dashbuddy.data.vehicle.api.dto.VehicleDetailsResponse
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VehicleRepository @Inject constructor(
    private val api: FuelEconomyApi
) {

    suspend fun getYears(): List<String> {
        return try {
            val response = api.getYears()
            response.menuItem.map { it.text }.sortedDescending()
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch vehicle years")
            emptyList()
        }
    }

    suspend fun getMakes(year: String): List<String> {
        return try {
            val response = api.getMakes(year = year)
            response.menuItem.map { it.text }.sorted()
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch vehicle makes for year: $year")
            emptyList()
        }
    }

    suspend fun getModels(year: String, make: String): List<String> {
        return try {
            val response = api.getModels(year = year, make = make)
            response.menuItem.map { it.text }.sorted()
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch vehicle models for $year $make")
            emptyList()
        }
    }

    // --- NEW: Fetch the raw MenuItems (so we keep the text AND the hidden ID) ---
    suspend fun getVehicleOptions(year: String, make: String, model: String): List<MenuItem> {
        return try {
            val response = api.getVehicleOptions(year = year, make = make, model = model)
            response.menuItem
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch vehicle options for $year $make $model")
            emptyList()
        }
    }

    // --- UPDATED: Fetch exact MPG using the specific Vehicle ID ---
    suspend fun getCombinedMpg(vehicleId: String): Float? {
        return try {
            val details = api.getVehicleDetails(vehicleId = vehicleId)
            Timber.d("Fetched MPG for ID $vehicleId: ${details.combinedMpg}")
            details.combinedMpg
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch MPG for ID $vehicleId")
            null
        }
    }

    // Inside VehicleRepository.kt
    // Rename and change the return type to the raw DTO (Assuming your DTO is named VehicleDetailsDto)
    suspend fun getVehicleDetails(vehicleId: String): VehicleDetailsResponse? {
        return try {
            val details = api.getVehicleDetails(vehicleId = vehicleId)
            Timber.d("Fetched Details for ID $vehicleId: MPG=${details.combinedMpg}, Fuel=${details.fuelType1}")
            details
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch Details for ID $vehicleId")
            null
        }
    }
}