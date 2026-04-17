package cloud.trotter.dashbuddy.core.network.vehicle.efficiency.epa

import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleDetails
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleOption
import cloud.trotter.dashbuddy.domain.provider.VehicleEfficiencyDataSource
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EPA fueleconomy.gov implementation of [VehicleEfficiencyDataSource].
 * Maps all EPA DTOs to pure :domain models so nothing above :core:network
 * ever sees a network-layer type.
 */
@Singleton
class EpaVehicleDataSource @Inject constructor(
    private val api: EpaApi
) : VehicleEfficiencyDataSource {

    override suspend fun getYears(): List<String> = try {
        api.getYears().menuItem.map { it.text }.sortedDescending()
    } catch (e: Exception) {
        Timber.e(e, "Failed to fetch vehicle years")
        emptyList()
    }

    override suspend fun getMakes(year: String): List<String> = try {
        api.getMakes(year = year).menuItem.map { it.text }.sorted()
    } catch (e: Exception) {
        Timber.e(e, "Failed to fetch vehicle makes for year: $year")
        emptyList()
    }

    override suspend fun getModels(year: String, make: String): List<String> = try {
        api.getModels(year = year, make = make).menuItem.map { it.text }.sorted()
    } catch (e: Exception) {
        Timber.e(e, "Failed to fetch vehicle models for $year $make")
        emptyList()
    }

    override suspend fun getVehicleOptions(
        year: String,
        make: String,
        model: String
    ): List<VehicleOption> = try {
        api.getVehicleOptions(year = year, make = make, model = model).menuItem
            .map { VehicleOption(id = it.value, displayName = it.text) }
    } catch (e: Exception) {
        Timber.e(e, "Failed to fetch vehicle options for $year $make $model")
        emptyList()
    }

    override suspend fun getVehicleDetails(vehicleId: String): VehicleDetails? = try {
        val response = api.getVehicleDetails(vehicleId = vehicleId)
        VehicleDetails(
            combinedMpg = response.combinedMpg,
            fuelType = mapFuelType(response.fuelType1)
        )
    } catch (e: Exception) {
        Timber.e(e, "Failed to fetch vehicle details for ID $vehicleId")
        null
    }

    private fun mapFuelType(epaString: String?): FuelType {
        if (epaString == null) return FuelType.REGULAR
        val lower = epaString.lowercase(Locale.getDefault())
        return when {
            lower.contains("electricity") -> FuelType.ELECTRICITY
            lower.contains("premium") -> FuelType.PREMIUM
            lower.contains("midgrade") -> FuelType.MIDGRADE
            lower.contains("diesel") -> FuelType.DIESEL
            else -> FuelType.REGULAR
        }
    }
}
