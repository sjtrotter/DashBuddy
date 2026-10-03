package cloud.trotter.dashbuddy.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.domain.census.CensusUploadScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CensusWorkScheduler @Inject constructor(@param:ApplicationContext private val context: Context) : CensusUploadScheduler {
    override fun enqueueNow(replaceQueued: Boolean) {
        if (!BuildConfig.DEBUG) return
        // KEEP would let a manual run parked in backoff swallow the request; a reset wants the fresh identity enrolled now.
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_NAME, if (replaceQueued) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CensusUploadWorker>().setConstraints(constraints()).build(),
        )
    }

    override fun enqueueSoon() {
        if (!BuildConfig.DEBUG) return
        // KEEP: the first spooled item sets the five-minute deadline; later items never push it out.
        WorkManager.getInstance(context).enqueueUniqueWork(
            SOON_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CensusUploadWorker>()
                .setConstraints(constraints())
                .setInitialDelay(SOON_DELAY_MINUTES, TimeUnit.MINUTES)
                .build(),
        )
    }

    override fun deferUntil(epochMillis: Long) {
        if (!BuildConfig.DEBUG) return
        // UPDATE preserves a running periodic worker. The persisted deadline also gates manual runs.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CensusUploadWorker>(60, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .setNextScheduleTimeOverride(epochMillis)
                .build(),
        )
    }

    companion object {
        const val PERIODIC_NAME = "census_upload"
        const val NOW_NAME = "census_upload_now"
        const val SOON_NAME = "census_upload_soon"
        const val SOON_DELAY_MINUTES = 5L

        private fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        fun schedule(context: Context) {
            if (!BuildConfig.DEBUG) return
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CensusUploadWorker>(60, TimeUnit.MINUTES)
                    .setConstraints(constraints()).build(),
            )
        }
    }
}
