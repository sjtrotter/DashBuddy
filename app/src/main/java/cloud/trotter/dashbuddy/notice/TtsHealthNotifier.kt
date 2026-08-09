package cloud.trotter.dashbuddy.notice

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.ui.main.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The dasher-visible half of the #991 speech-engine liveness signal: when the offer voice has gone
 * silent and re-initialization hasn't brought it back, say so instead of letting the dasher drive
 * on believing nothing worth speaking has arrived.
 *
 * The fifth member of the #909 silent-death family, and the same shape as its recognition sibling
 * [RecognitionHealthNotifier] — shared `AppNoticeChannel` (a channel per diagnostic would be
 * settings noise, and these all share the "you'd want to know this" character), own notification
 * id, fail-open at every step.
 *
 * **Stateless by design.** "Only once per process" is [cloud.trotter.dashbuddy.state.effects.TtsRecoveryPolicy]'s
 * latch; a second gate here would be a second source of truth for "have we already said this".
 *
 * **PII posture:** the copy is fixed device-independent text — no offer, store, customer or screen
 * value ever reaches it, so principle 7's INFO+ contract holds trivially.
 */
@Singleton
class TtsHealthNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
) {

    /** Post the one notice. Never throws: the caller is an effect handler mid-offer. */
    fun onSpeechEngineUnrecoverable() {
        try {
            postNotice()
        } catch (t: Throwable) {
            // Deliberately broad and NOT rethrown (#909): a refused notification (no
            // POST_NOTIFICATIONS grant) must degrade to the handler's WARN, never to a thrown
            // effect.
            Timber.tag(TAG).e(t, "Speech-engine notice could not be posted (#991)")
        }
    }

    private fun postNotice() {
        AppNoticeChannel.ensure(context, notificationManager)

        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = context.getString(R.string.tts_health_notice_text)
        val notification = NotificationCompat.Builder(context, AppNoticeChannel.ID)
            .setSmallIcon(R.drawable.bag_red_idle)
            .setContentTitle(context.getString(R.string.tts_health_notice_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(AppNoticeChannel.Ids.TTS_ENGINE_HEALTH, notification)
    }

    private companion object {
        const val TAG = "Tts"
        const val REQUEST_CODE = 991
    }
}
