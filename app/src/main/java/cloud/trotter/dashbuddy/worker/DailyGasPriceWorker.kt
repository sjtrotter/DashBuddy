package cloud.trotter.dashbuddy.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.trotter.dashbuddy.core.data.fuel.FuelPriceRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * A background job managed by Android's WorkManager.
 * Runs approximately once every 24 hours to keep the local gas price updated.
 */
@HiltWorker
class DailyGasPriceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val gasPriceRepository: FuelPriceRepository,
    private val appPreferencesRepository: AppPreferencesRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.tag("Network").i("Waking up to run DailyGasPriceWorker...")

        return try {
            // 1. Check if the user actually wants us to auto-update.
            // Using .first() grabs the current snapshot of the Flow.
            val isAuto = appPreferencesRepository.isGasPriceAuto.first()
            if (!isAuto) {
                Timber.tag("Network").i("Auto gas price is disabled in Settings. Going back to sleep.")
                return Result.success() // Tell Android the job is "done"
            }

            // 2. Figure out what fuel type the user drives right now
            val fuelType = appPreferencesRepository.fuelType.first()

            // 3. Hit the EIA API and save it to DataStore
            Timber.tag("Network").i("Fetching latest gas price for ${fuelType.name}...")
            val result = gasPriceRepository.fetchAndSaveCurrentGasPrice(fuelType)

            // 4. Report back to the Android OS
            if (result.isSuccess) {
                Timber.tag("Network").i("Successfully updated gas prices in background.")
                Result.success()
            } else {
                // #692 P7: ONE WARN line for this failure now (folded with the ERROR that used to
                // fire inside EiaFuelPrice for the same event) — carries the underlying reason so
                // folding the two lines loses no diagnostic value. A WorkManager-retried network
                // fetch is not lost data / a crashed subsystem (it succeeded yesterday and
                // self-heals), so WARN — not ERROR — is the right level. (#348: exceptionOrNull's
                // message never contains the api_key — it's either our own "empty/invalid data" /
                // "location not available" text or the underlying network exception's message, none
                // of which echo the request's query params.)
                Timber.tag("Network").w(
                    "Failed to fetch gas prices (%s) — WorkManager will retry later.",
                    result.exceptionOrNull()?.message ?: "unknown reason",
                )
                Result.retry() // Tells Android to try again during the next maintenance window
            }

        } catch (e: Exception) {
            Timber.tag("Network").e(e, "Fatal error executing DailyGasPriceWorker")
            Result.failure() // Hard fail, don't retry until the next 24-hour cycle
        }
    }
}