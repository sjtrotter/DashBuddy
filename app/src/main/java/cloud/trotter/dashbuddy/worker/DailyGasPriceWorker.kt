package cloud.trotter.dashbuddy.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.trotter.dashbuddy.data.gas.GasPriceRepository
import cloud.trotter.dashbuddy.data.settings.SettingsRepository
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
    private val gasPriceRepository: GasPriceRepository,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.i("Waking up to run DailyGasPriceWorker...")

        return try {
            // 1. Check if the user actually wants us to auto-update.
            // Using .first() grabs the current snapshot of the Flow.
            val isAuto = settingsRepository.isGasPriceAuto.first()
            if (!isAuto) {
                Timber.i("Auto gas price is disabled in Settings. Going back to sleep.")
                return Result.success() // Tell Android the job is "done"
            }

            // 2. Figure out what fuel type the user drives right now
            val fuelType = settingsRepository.fuelType.first()

            // 3. Hit the EIA API and save it to DataStore
            Timber.i("Fetching latest gas price for ${fuelType.name}...")
            val result = gasPriceRepository.fetchAndSaveCurrentGasPrice(fuelType)

            // 4. Report back to the Android OS
            if (result.isSuccess) {
                Timber.i("Successfully updated gas prices in background.")
                Result.success()
            } else {
                Timber.w("Failed to fetch gas prices. WorkManager will retry later.")
                Result.retry() // Tells Android to try again during the next maintenance window
            }

        } catch (e: Exception) {
            Timber.e(e, "Fatal error executing DailyGasPriceWorker")
            Result.failure() // Hard fail, don't retry until the next 24-hour cycle
        }
    }
}