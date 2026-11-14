package cloud.trotter.log.gig.notice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.data.census.contributor.CensusContributor
import cloud.trotter.log.gig.domain.census.contributor.ContributorEnd
import cloud.trotter.log.gig.domain.census.contributor.ContributorRecord
import cloud.trotter.log.gig.domain.census.contributor.ShiftActivity
import cloud.trotter.log.gig.domain.di.ApplicationScope
import cloud.trotter.log.gig.ui.main.MainActivity
import cloud.trotter.log.gig.ui.main.navigation.Screen
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #1625: posts the screen-review invitation when the last running shift ends, never during one and never at app start
 * (only a running-to-ended change counts), [SETTLE_MILLIS] later. Whether to post is [CensusContributor.inviteAfterShift]'s decision, which
 * also records the offer so it posts at most once until the dasher answers. The tap opens the reviewer screen.
 *
 * Its own "Census" channel: muting the invitation must not mute the App notices warnings. Fail-open: a post the OS
 * refuses logs and returns; the Census screen's row still shows the invitation.
 */
@Singleton
class ContributorInvitationNotifier internal constructor(
    private val shifts: ShiftActivity,
    private val invite: suspend () -> ContributorRecord?,
    private val scope: CoroutineScope,
    private val post: (renewal: Boolean) -> Unit,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        notificationManager: NotificationManager,
        contributor: CensusContributor,
        shifts: ShiftActivity,
        @ApplicationScope scope: CoroutineScope,
    ) : this(shifts, contributor::inviteAfterShift, scope, { renewal -> postNotification(context, notificationManager, renewal) })

    fun start(): Job = scope.launch { watch(shifts.shiftLive) }

    /**
     * Waits [SETTLE_MILLIS] after a shift ends, so the analytics read model has folded the shift that just ended
     * before eligibility is read; a shift that starts meanwhile cancels the wait.
     */
    internal suspend fun watch(shiftLive: Flow<Boolean>) = afterShiftEnds(shiftLive, SETTLE_MILLIS) {
        invite()?.let { record ->
            try {
                post(record.ended == ContributorEnd.EXPIRED)
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Census review invitation could not be posted (#1625)")
            }
        }
    }

    companion object {
        private const val TAG = "Census"
        internal const val SETTLE_MILLIS = 30_000L

        /** Stable channel id. Do not rename: the dasher's mute state is keyed on it. */
        const val CHANNEL_ID = "census_channel"

        /** In the small-id space beside the [AppNoticeChannel] notices (102, 103, 105) and the weekly plan (104). */
        const val NOTIFICATION_ID = 106
        private const val REQUEST_CODE = 1625

        private fun postNotification(context: Context, manager: NotificationManager, renewal: Boolean) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.census_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.census_channel_description) },
            )
            val title = context.getString(
                if (renewal) R.string.census_review_invitation_renew_title else R.string.census_review_invitation_title,
            )
            val text = context.getString(
                if (renewal) R.string.census_review_invitation_renew_text else R.string.census_review_invitation_text,
            )
            val contentIntent = PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                MainActivity.routeIntent(context, Screen.CensusReviewer.route),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.bag_red_idle)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}

/**
 * #1625/#1626: runs [block] [settleMillis] after the last running shift ends, never at app start (only a running-to-ended
 * change counts); a shift that starts meanwhile cancels the wait.
 */
internal suspend fun afterShiftEnds(shiftLive: Flow<Boolean>, settleMillis: Long, block: suspend () -> Unit) {
    var running = false
    shiftLive.collectLatest { live ->
        val ended = running && !live
        running = live
        if (!ended) return@collectLatest
        delay(settleMillis)
        block()
    }
}
