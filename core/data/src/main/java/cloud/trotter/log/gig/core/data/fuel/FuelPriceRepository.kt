package cloud.trotter.log.gig.core.data.fuel

import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.location.LocationDataSource
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import cloud.trotter.log.gig.domain.provider.FuelPriceDataSource
import timber.log.Timber
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FuelPriceRepository @Inject constructor(
    private val gasDataSource: FuelPriceDataSource,
    private val locationDataSource: LocationDataSource,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val clock: Clock,
) {
    suspend fun fetchAndSaveCurrentGasPrice(fuelType: FuelType): Result<Float> =
        fetchAndPersist(fuelType) { price, at -> appPreferencesRepository.updateGasPrice(price, at) }

    /**
     * "Resume auto" (#722) — the bubble's MANUAL-mode chip. Routes through the SAME fetch as
     * [fetchAndSaveCurrentGasPrice] (no second fetch path); only the save differs — it re-enables
     * auto atomically with the fetched price via [AppPreferencesRepository.updateGasPriceAuto],
     * the inverse of the stepper's [AppPreferencesRepository.updateGasPriceManual] flip.
     */
    suspend fun fetchAndResumeAutoGasPrice(fuelType: FuelType): Result<Float> =
        fetchAndPersist(fuelType) { price, at -> appPreferencesRepository.updateGasPriceAuto(price, at) }

    /**
     * A failed persist is a failed [Result], never an uncaught throw — callers rely on the
     * no-throw contract (the bubble's `viewModelScope.launch` has no exception handler, and the
     * daily worker treats a failure Result as a retry signal). The price is stamped with the clock's time
     * when the lookup returned; a failed lookup writes nothing.
     */
    private suspend fun fetchAndPersist(fuelType: FuelType, save: suspend (Float, Long) -> Unit): Result<Float> =
        fetchCurrentGasPrice(fuelType).mapCatching { newPrice ->
            try {
                save(newPrice, clock.millis())
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "GasPriceRepository failed to persist fetched price")
                throw e
            }
            newPrice
        }

    suspend fun fetchGasPriceOnly(fuelType: FuelType): Result<Float> {
        return try {
            val userLocation = locationDataSource.getUserLocation()
            gasDataSource.getFuelPrice(userLocation, fuelType)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "GasPriceRepository failed to fetch price only")
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
            Timber.tag(TAG).e(e, "GasPriceRepository failed to update price")
            Result.failure(e)
        }
    }

    private companion object {
        private const val TAG = "FuelPrice"
    }
}