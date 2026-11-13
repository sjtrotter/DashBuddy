package cloud.trotter.log.gig.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cloud.trotter.log.gig.domain.census.CensusUploadScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The census's WorkManager half, in every build (#1620). The one scheduled run is the daily window
 * ([cloud.trotter.log.gig.core.data.census.CensusUploadPlanner]); a manual run is the test build's Upload now.
 */
@Singleton
class CensusWorkScheduler @Inject constructor(@param:ApplicationContext private val context: Context) : CensusUploadScheduler {
    override fun enqueueNow(wifiOnly: Boolean, replaceQueued: Boolean) {
        // KEEP would let a manual run parked in backoff swallow the request; a reset wants the fresh identity enrolled now.
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_NAME, if (replaceQueued) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CensusUploadWorker>().setConstraints(constraints(wifiOnly)).build(),
        )
    }

    override fun scheduleDaily(runAtMillis: Long, wifiOnly: Boolean) {
        val workManager = WorkManager.getInstance(context)
        // The hourly and five-minute runs from before #1620; cancelling a name that is not queued does nothing.
        LEGACY_NAMES.forEach { workManager.cancelUniqueWork(it) }
        // UPDATE keeps a running worker and replaces only the next run's time and constraints.
        workManager.enqueueUniquePeriodicWork(DAILY_NAME, ExistingPeriodicWorkPolicy.UPDATE, dailyRequest(runAtMillis, wifiOnly))
    }

    companion object {
        const val DAILY_NAME = "census_daily"
        /** The daily run's tag: the worker draws the next day's time only on this run, never on a manual one. */
        const val DAILY_TAG = "census_daily"
        const val NOW_NAME = "census_upload_now"
        private val LEGACY_NAMES = listOf("census_upload", "census_upload_soon")

        /** Wi-Fi only waits for an unmetered network; otherwise any connection will do. */
        internal fun constraints(wifiOnly: Boolean): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        internal fun dailyRequest(runAtMillis: Long, wifiOnly: Boolean): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<CensusUploadWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints(wifiOnly))
                .setNextScheduleTimeOverride(runAtMillis)
                .addTag(DAILY_TAG)
                .build()
    }
}
