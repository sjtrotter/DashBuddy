package cloud.trotter.dashbuddy.data.gas

import cloud.trotter.dashbuddy.core.location.LocationDataSource
import cloud.trotter.dashbuddy.data.settings.SettingsRepository
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.provider.FuelPriceDataSource
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GasPriceRepository @Inject constructor(
    private val gasDataSource: FuelPriceDataSource,
    private val locationDataSource: LocationDataSource,
    private val settingsRepository: SettingsRepository
) {
    suspend fun fetchAndSaveCurrentGasPrice(fuelType: FuelType): Result<Float> {
        return try {
            // Note: Make sure to add `suspend fun getLastKnownLocation(): Location?` to your LocationDataSource interface!
            val userLocation = locationDataSource.getUserLocation() ?: return Result.failure(
                IllegalStateException("Location not available")
            )
            val priceResult = gasDataSource.getFuelPrice(userLocation, fuelType)

            if (priceResult.isSuccess) {
                val newPrice = priceResult.getOrThrow()
                settingsRepository.updateGasPrice(newPrice)
            }

            priceResult
        } catch (e: Exception) {
            Timber.e(e, "GasPriceRepository failed to update price")
            Result.failure(e)
        }
    }

    suspend fun fetchGasPriceOnly(fuelType: FuelType): Result<Float> {
        return try {
            val userLocation = locationDataSource.getUserLocation()
            gasDataSource.getFuelPrice(userLocation, fuelType)
        } catch (e: Exception) {
            Timber.e(e, "GasPriceRepository failed to fetch price only")
            Result.failure(e)
        }
    }
}