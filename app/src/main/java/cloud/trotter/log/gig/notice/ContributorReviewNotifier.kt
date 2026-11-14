package cloud.trotter.log.gig.notice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.data.census.contributor.ContributorReviewQueue
import cloud.trotter.log.gig.domain.census.contributor.ContributorGate
import cloud.trotter.log.gig.domain.census.contributor.ShiftActivity
import cloud.trotter.log.gig.domain.di.ApplicationScope
import cloud.trotter.log.gig.ui.main.MainActivity
import cloud.trotter.log.gig.ui.main.navigation.Screen
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * #1626: after a shift ends, says that screens are ready for review: only with a live grant ([ContributorGate.mayUpload])
 * and a screen the server stored on an earlier UTC day, at most once a UTC day per process. The tap opens Screen
 * reviews, which lists them. Same "Census" channel as the invitation; fail-open like it.
 */
@Singleton
class ContributorReviewNotifier internal constructor(
    private val shifts: ShiftActivity,
    private val ready: suspend () -> LocalDate?,
    private val scope: CoroutineScope,
    private val post: () -> Unit,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        notificationManager: NotificationManager,
        queue: ContributorReviewQueue,
        gate: ContributorGate,
        shifts: ShiftActivity,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        shifts,
        {
            val today = queue.todayUtc()
            today.takeIf { queue.entries.first().any { it.readyOn(today) } && gate.mayUpload() }
        },
        scope,
        { postNotification(context, notificationManager) },
    )

    private var postedOn: LocalDate? = null

    fun start(): Job = scope.launch { watch() }

    internal suspend fun watch() = afterShiftEnds(shifts.shiftLive, ContributorInvitationNotifier.SETTLE_MILLIS) {
        val day = ready() ?: return@afterShiftEnds
        if (day == postedOn) return@afterShiftEnds
        try {
            post()
            postedOn = day
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Census review notice could not be posted (#1626)")
        }
    }

    companion object {
        private const val TAG = "Census"

        /** Beside the invitation (106) in the small-id space. */
        const val NOTIFICATION_ID = 107
        private const val REQUEST_CODE = 1626

        private fun postNotification(context: Context, manager: NotificationManager) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ContributorInvitationNotifier.CHANNEL_ID,
                    context.getString(R.string.census_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.census_channel_description) },
            )
            val text = context.getString(R.string.census_review_ready_text)
            val contentIntent = PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                MainActivity.routeIntent(context, Screen.CensusReviewer.route),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, ContributorInvitationNotifier.CHANNEL_ID)
                .setSmallIcon(R.drawable.bag_red_idle)
                .setContentTitle(context.getString(R.string.census_review_ready_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
