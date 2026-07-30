package cloud.trotter.dashbuddy.notice

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.pipeline.RecognitionHealthReporter
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.ui.main.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The dasher-visible half of the #937 recognition-health alarm: when a platform's screens stop
 * being recognized, say so out loud instead of quietly narrating nothing.
 *
 * This is the recognition entry in the #909 silent-death family. The effect engine (#914), the
 * bubble (#916) and the odometer (#917) each got a liveness signal; recognition had none, so a
 * platform app update that broke every anchor mid-dash was detectable only at the next desk
 * pull — and once rules ship OTA (#640) to users who never do a desk pull, never at all.
 *
 * **Stateless by design.** The once-per-platform-per-process edge gate lives in
 * `RecognitionHealthMonitor` (`:core:pipeline`), which owns the window that decides; duplicating
 * a gate here would be a second source of truth for "have we already said this".
 *
 * **Fail-open.** Every path is wrapped: this is called from the sensing hot path, and a
 * notification the OS refuses (POST_NOTIFICATIONS not granted) must degrade to the monitor's
 * WARN alone, never to a thrown frame.
 *
 * **PII posture:** the only values that reach the notification are a platform's own display
 * name (resolved from its wire through the `Platform` registry) and a percentage. No screen
 * text, no customer data, no store names — principle 7's INFO+ contract holds trivially.
 */
@Singleton
class RecognitionHealthNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
) : RecognitionHealthReporter {

    override fun onRecognitionDegraded(platformWire: String, unknownPercent: Int) {
        try {
            postNotice(platformWire, unknownPercent)
        } catch (t: Throwable) {
            // Deliberately broad and NOT rethrown (#909): the caller is a sensing frame.
            Timber.tag(TAG).e(t, "Recognition health notice could not be posted (#937)")
        }
    }

    private fun postNotice(platformWire: String, unknownPercent: Int) {
        AppNoticeChannel.ensure(context, notificationManager)

        // The registry is the only path from a wire to display copy (principle 8). An unknown
        // wire — a ruleset naming a platform this build doesn't carry — still gets a notice,
        // with the generic wording rather than a fabricated brand name.
        val platformName = Platform.fromWire(platformWire)
            ?.takeIf { it != Platform.Unknown }
            ?.displayName
            ?: context.getString(R.string.recognition_health_notice_platform_fallback)

        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = context.getString(
            R.string.recognition_health_notice_text, platformName, unknownPercent,
        )
        val notification = NotificationCompat.Builder(context, AppNoticeChannel.ID)
            .setSmallIcon(R.drawable.bag_red_idle)
            .setContentTitle(context.getString(R.string.recognition_health_notice_title, platformName))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(AppNoticeChannel.Ids.RECOGNITION_HEALTH, notification)
    }

    private companion object {
        const val TAG = "RecognitionHealth"
        const val REQUEST_CODE = 937
    }
}
