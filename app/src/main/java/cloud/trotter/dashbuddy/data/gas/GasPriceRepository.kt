package cloud.trotter.dashbuddy.data.gas

import cloud.trotter.dashbuddy.data.location.LocationDataSource
import cloud.trotter.dashbuddy.data.settings.SettingsRepository
import cloud.trotter.dashbuddy.model.vehicle.FuelType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GasPriceRepository @Inject constructor(
    private val gasDataSource: GasPriceDataSource,
    private val locationDataSource: LocationDataSource,
    private val settingsRepository: SettingsRepository
) {
    suspend fun fetchAndSaveCurrentGasPrice(fuelType: FuelType): Result<Float> {
        return try {
            // Note: Make sure to add `suspend fun getLastKnownLocation(): Location?` to your LocationDataSource interface!
            val location = locationDataSource.getLastKnownLocation()
            val lat = location?.latitude
            val lon = location?.longitude

            val priceResult = gasDataSource.getGasPrice(lat, lon, fuelType)

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
            val location = locationDataSource.getLastKnownLocation()
            val lat = location?.latitude
            val lon = location?.longitude

            gasDataSource.getGasPrice(lat, lon, fuelType)
        } catch (e: Exception) {
            Timber.e(e, "GasPriceRepository failed to fetch price only")
            Result.failure(e)
        }
    }
}