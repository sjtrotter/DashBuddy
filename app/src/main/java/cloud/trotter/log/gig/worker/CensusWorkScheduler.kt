package cloud.trotter.log.gig.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
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
 * ([cloud.trotter.log.gig.core.data.census.CensusUploadPlanner]); a manual run is the test build's Upload now; a
 * deletion run retries a pending "Delete my census data" request (#1622).
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

    override fun cancelManual() {
        WorkManager.getInstance(context).cancelUniqueWork(NOW_NAME)
    }

    override fun enqueueDeletion() {
        // REPLACE: a new request restarts the backoff instead of waiting behind an old one's.
        WorkManager.getInstance(context).enqueueUniqueWork(DELETION_NAME, ExistingWorkPolicy.REPLACE, deletionRequest())
    }

    companion object {
        const val DAILY_NAME = "census_daily"
        /** The daily run's tag: the worker draws the next day's time only on this run, never on a manual one. */
        const val DAILY_TAG = "census_daily"
        const val NOW_NAME = "census_upload_now"
        /** #1622: the deletion retry; a run with this tag sends the pending deletion request and nothing else. */
        const val DELETION_NAME = "census_delete"
        const val DELETION_TAG = "census_delete"
        private val LEGACY_NAMES = listOf("census_upload", "census_upload_soon")

        /** Wi-Fi only waits for an unmetered network; otherwise any connection will do. */
        internal fun constraints(wifiOnly: Boolean): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** A deletion request carries no census data, so any connection will do, whatever the Wi-Fi only choice. */
        internal fun deletionRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<CensusUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(DELETION_TAG)
            .build()

        internal fun dailyRequest(runAtMillis: Long, wifiOnly: Boolean): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<CensusUploadWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints(wifiOnly))
                .setNextScheduleTimeOverride(runAtMillis)
                .addTag(DAILY_TAG)
                .build()
    }
}
