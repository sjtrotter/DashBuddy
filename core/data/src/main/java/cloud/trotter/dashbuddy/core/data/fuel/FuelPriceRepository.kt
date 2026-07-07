package cloud.trotter.dashbuddy.core.data.fuel

import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.location.LocationDataSource
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.provider.FuelPriceDataSource
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FuelPriceRepository @Inject constructor(
    private val gasDataSource: FuelPriceDataSource,
    private val locationDataSource: LocationDataSource,
    private val appPreferencesRepository: AppPreferencesRepository
) {
    suspend fun fetchAndSaveCurrentGasPrice(fuelType: FuelType): Result<Float> {
        val priceResult = fetchCurrentGasPrice(fuelType)
        priceResult.onSuccess { newPrice -> appPreferencesRepository.updateGasPrice(newPrice) }
        return priceResult
    }

    /**
     * "Resume auto" (#722) — the bubble's MANUAL-mode chip. Routes through the SAME fetch as
     * [fetchAndSaveCurrentGasPrice] (no second fetch path); only the save differs — it re-enables
     * auto atomically with the fetched price via [AppPreferencesRepository.updateGasPriceAuto],
     * the inverse of the stepper's [AppPreferencesRepository.updateGasPriceManual] flip.
     */
    suspend fun fetchAndResumeAutoGasPrice(fuelType: FuelType): Result<Float> {
        val priceResult = fetchCurrentGasPrice(fuelType)
        priceResult.onSuccess { newPrice -> appPreferencesRepository.updateGasPriceAuto(newPrice) }
        return priceResult
    }

    suspend fun fetchGasPriceOnly(fuelType: FuelType): Result<Float> {
        return try {
            val userLocation = locationDataSource.getUserLocation()
            gasDataSource.getFuelPrice(userLocation, fuelType)
        } catch (e: Exception) {
            Timber.Forest.e(e, "GasPriceRepository failed to fetch price only")
            Result.failure(e)
        }
    }

    /** The one EIA fetch path both save variants above route through — SSOT (#722). */
    private suspend fun fetchCurrentGasPrice(fuelType: FuelType): Result<Float> {
        return try {
            // Note: Make sure to add `suspend fun getLastKnownLocation(): Location?` to your LocationDataSource interface!
            val userLocation = locationDataSource.getUserLocation() ?: return Result.failure(
                IllegalStateException("Location not available")
            )
            gasDataSource.getFuelPrice(userLocation, fuelType)
        } catch (e: Exception) {
            Timber.Forest.e(e, "GasPriceRepository failed to update price")
            Result.failure(e)
        }
    }
}