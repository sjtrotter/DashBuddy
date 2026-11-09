package cloud.trotter.log.gig.notice

import android.app.NotificationManager
import android.content.Context
import cloud.trotter.log.gig.domain.pipeline.RecognitionHealthReporter
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncident
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The dasher-visible half of the recognition incident (#937, one incident per platform since #1560):
 * when a platform's screens stop being recognized, or its rules stop reading them, say so out loud
 * instead of quietly narrating nothing.
 *
 * This is the recognition entry in the #909 silent-death family. The effect engine (#914), the
 * bubble (#916) and the odometer (#917) each got a liveness signal; recognition had none, so a
 * platform app update that broke every anchor mid-dash was detectable only at the next desk
 * pull — and once rules ship OTA (#640) to users who never do a desk pull, never at all.
 *
 * **One notice per platform.** Every notice posts on [AppNoticeChannel.Ids.RECOGNITION_HEALTH] tagged
 * by the platform wire, so two platforms' notices coexist and a close withdraws only its own. The notice
 * is posted once per incident and not updated when causes join; Home's card shows every cause.
 *
 * **The #640 seam.** This is the one place the notice is built. The "Check for new rules" / "Keep rules
 * up to date" actions #640 adds go here, beside the copy, so the `:domain` contract stays data only.
 *
 * **Stateless by design.** The incident, its one-notice gate and the once-a-day limit live in
 * `RecognitionHealthMonitor` (`:core:pipeline`); duplicating a gate here would be a second source of
 * truth for "have we already said this".
 *
 * **Fail-open.** Every path is wrapped: a notification the OS refuses (POST_NOTIFICATIONS not granted)
 * must degrade to the monitor's log lines alone, never to a crashed reporter coroutine.
 *
 * **PII posture:** the copy comes from [RecognitionNoticeCopy]: a platform's own display name
 * (resolved from its wire through the `Platform` registry) and our own shape and field labels. No
 * screen text, no customer data, no store names, no percentage.
 */
@Singleton
class RecognitionHealthNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
) : RecognitionHealthReporter {

    override fun onIncidentNotice(incident: RecognitionIncident) {
        try {
            AppNoticeChannel.postNotice(
                context = context,
                notificationManager = notificationManager,
                notificationId = AppNoticeChannel.Ids.RECOGNITION_HEALTH,
                requestCode = REQUEST_CODE,
                title = RecognitionNoticeCopy.title(context.resources, incident),
                text = RecognitionNoticeCopy.text(context.resources, incident),
                tag = incident.platformWire,
            )
        } catch (t: Throwable) {
            // Deliberately broad and NOT rethrown (#909): a refused notice costs a log line.
            Timber.tag(TAG).e(t, "Recognition notice could not be posted (#1560)")
        }
    }

    override fun onIncidentClosed(platformWire: String) {
        try {
            AppNoticeChannel.cancelNotice(notificationManager, platformWire, AppNoticeChannel.Ids.RECOGNITION_HEALTH)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Recognition notice could not be withdrawn (#1560)")
        }
    }

    private companion object {
        const val TAG = "RecognitionHealth"
        const val REQUEST_CODE = 937
    }
}
