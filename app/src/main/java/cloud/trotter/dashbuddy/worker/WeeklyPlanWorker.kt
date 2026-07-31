package cloud.trotter.dashbuddy.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.WeeklyPlanRepository
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanGrade
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanGrader
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanSchedule
import cloud.trotter.dashbuddy.notice.WeeklyPlanNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The Sunday-evening Weekly Plan nudge (#981 / brief §8's trigger), and the loop that makes it worth
 * keeping enabled: before inviting the driver to plan the week ahead, it **grades the plan for the
 * week just ending** against their own record.
 *
 * ### Scheduling
 *
 * WorkManager is already a dependency and already hosts a periodic job ([DailyGasPriceWorker]), so
 * this needs no new mechanism, no exact-alarm permission, and no boot receiver: WorkManager persists
 * its queue in its own database and re-schedules across reboot itself.
 *
 * The request is **periodic on a 7-day interval with an initial delay computed to the next Sunday
 * 18:00 local** by the pure `:domain` [WeeklyPlanSchedule]. Periodic work is what re-arms itself
 * safely — a one-time job that re-enqueued its own unique name would have to `REPLACE` work that is
 * *currently running* (itself), which cancels the very run doing the enqueue.
 *
 * **Documented residual:** a 7-day interval is 7×24 h, so a DST change drifts the arrival by an hour
 * (6 pm becomes 5 or 7) until the next app launch re-anchors it — [schedule] recomputes the delay from
 * the wall clock every time. The brief asks for "Sunday ~6 PM"; an hour of drift twice a year is
 * inside that, and buying exactness would mean an exact-alarm permission this feature has no business
 * requesting.
 *
 * **Never takes the loop down with it.** The whole body is wrapped and the worker returns `success`
 * even when the read or the notification failed — a background job that fails hard because one week's
 * grade threw would silently end the feature (the #909 silent-death lesson in miniature).
 */
@HiltWorker
class WeeklyPlanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val analyticsRepository: AnalyticsRepository,
    private val weeklyPlanRepository: WeeklyPlanRepository,
    private val notifier: WeeklyPlanNotifier,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        try {
            notifier.postWeeklyNudge(gradeForFinishedWeek())
        } catch (t: Throwable) {
            // Broad by intent (#909): nothing this job does is worth losing the weekly loop over.
            Timber.tag(TAG).e(t, "Weekly plan nudge failed")
        }
        return Result.success()
    }

    /**
     * Last week's saved plan measured against the record, or null when the driver saved none.
     *
     * Both reads are one-shot `first()` snapshots — a worker has no UI to keep collecting for — and
     * the grade itself is the pure `:domain` [WeeklyPlanGrader] over the same lifetime samples the
     * screen uses, so the notification and the screen can never quote different numbers.
     */
    private suspend fun gradeForFinishedWeek(): WeeklyPlanGrade? {
        val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDate()
        val plan = weeklyPlanRepository.planForNow(WeeklyPlanSchedule.gradedWeekStart(today)) ?: return null
        return WeeklyPlanGrader.grade(plan, analyticsRepository.hourOfWeekSamples().first())
    }

    companion object {
        private const val TAG = "WeeklyPlan"

        /** Unique work name — one weekly nudge schedule at a time. */
        const val WORK_NAME = "weekly_plan_nudge"

        private const val INTERVAL_DAYS = 7L

        /**
         * Arm (or re-anchor) the weekly nudge. Idempotent and safe to call on every app start.
         *
         * `CANCEL_AND_REENQUEUE` rather than `KEEP`: the delay is computed against the wall clock at
         * call time, so a launch after a timezone change, a reboot, or a DST shift re-pins it to the
         * real next Sunday instead of honouring whatever the old schedule had drifted to.
         */
        fun schedule(
            context: Context,
            nowMillis: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault(),
        ) {
            val delayMs = (WeeklyPlanSchedule.nextTriggerMillis(nowMillis, zone) - nowMillis).coerceAtLeast(0L)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                PeriodicWorkRequestBuilder<WeeklyPlanWorker>(INTERVAL_DAYS, TimeUnit.DAYS)
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build(),
            )
        }
    }
}
