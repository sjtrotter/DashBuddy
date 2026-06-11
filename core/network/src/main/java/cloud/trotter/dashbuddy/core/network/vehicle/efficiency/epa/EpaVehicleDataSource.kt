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

    override suspend fun getYears(): Result<List<String>> = runCatching {
        api.getYears().menuItem.map { it.text }.sortedDescending()
    }.onFailure { Timber.e(it, "Failed to fetch vehicle years") }

    override suspend fun getMakes(year: String): Result<List<String>> = runCatching {
        api.getMakes(year = year).menuItem.map { it.text }.sorted()
    }.onFailure { Timber.e(it, "Failed to fetch vehicle makes for year: $year") }

    override suspend fun getModels(year: String, make: String): Result<List<String>> = runCatching {
        api.getModels(year = year, make = make).menuItem.map { it.text }.sorted()
    }.onFailure { Timber.e(it, "Failed to fetch vehicle models for $year $make") }

    override suspend fun getVehicleOptions(
        year: String,
        make: String,
        model: String
    ): Result<List<VehicleOption>> = runCatching {
        api.getVehicleOptions(year = year, make = make, model = model).menuItem
            .map { VehicleOption(id = it.value, displayName = it.text) }
    }.onFailure { Timber.e(it, "Failed to fetch vehicle options for $year $make $model") }

    override suspend fun getVehicleDetails(vehicleId: String): Result<VehicleDetails> = runCatching {
        val response = api.getVehicleDetails(vehicleId = vehicleId)
        val fuel = mapFuelType(response.fuelType1)
        // EVs are detected via fuel type; otherwise infer class from EPA VClass.
        val vClass = if (fuel == FuelType.ELECTRICITY) {
            cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass.EV
        } else {
            mapEpaVClass(response.vClass)
        }
        VehicleDetails(
            combinedMpg = response.combinedMpg,
            fuelType = fuel,
            vehicleClass = vClass,
        )
    }.onFailure { Timber.e(it, "Failed to fetch vehicle details for ID $vehicleId") }

    private fun mapFuelType(epaString: String?): FuelType {
        if (epaString == null) return FuelType.REGULAR
        // Locale.ROOT (#405): EPA wire strings must not pass through the
        // device locale (Turkish-I breaks every match).
        val lower = epaString.lowercase(Locale.ROOT)
        return when {
            lower.contains("electricity") -> FuelType.ELECTRICITY
            lower.contains("premium") -> FuelType.PREMIUM
            lower.contains("midgrade") -> FuelType.MIDGRADE
            lower.contains("diesel") -> FuelType.DIESEL
            else -> FuelType.REGULAR
        }
    }
}
