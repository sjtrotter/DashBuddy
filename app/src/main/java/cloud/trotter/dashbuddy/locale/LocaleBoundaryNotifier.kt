package cloud.trotter.dashbuddy.locale

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.notice.AppNoticeChannel
import cloud.trotter.dashbuddy.ui.main.MainActivity
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import cloud.trotter.dashbuddy.domain.pipeline.LocaleBoundaryCheck
import cloud.trotter.dashbuddy.domain.pipeline.LocaleBoundaryReporter
import cloud.trotter.dashbuddy.domain.pipeline.RecognitionLocale
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The side-effecting half of the #938 English-locale boundary detection: one WARN per sensor
 * start plus a **once-per-install** dasher-visible notice when the device language is outside
 * the recognition/marker vocabulary.
 *
 * The decision itself is pure and lives in `:domain` ([RecognitionLocale]); this class owns only
 * the edges — the current locale, the DataStore flag, the log line, and the notification — so the
 * behaviour under test is the pure half plus three thin branches here.
 *
 * **Fail-open by construction.** [onSensorServiceConnected] returns immediately (the flag read is
 * a suspend DataStore call, so the work is dispatched onto the app scope) and every step is
 * wrapped: a locale diagnostic must never be able to stop sensing from starting, and a
 * notification the OS refuses (POST_NOTIFICATIONS not granted) degrades to the WARN alone.
 *
 * **PII posture:** the only device-derived value that reaches a log line or the notification is an
 * ISO-639 language code — principle 7's INFO+ contract holds trivially.
 */
@Singleton
class LocaleBoundaryNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
    private val appStateRepository: AppStateRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) : LocaleBoundaryReporter {

    override fun onSensorServiceConnected() {
        // Dispatched, never inline: the flag read is a suspend DataStore call and this runs on the
        // service's connected callback (main thread).
        scope.launch { report(Locale.getDefault()) }
    }

    /**
     * The whole decision + side effects for one sensor start. Takes the locale as a parameter
     * (rather than reading the JVM default here) so the test drives it without mutating global
     * state — the same shape `TimeFormats`/`Formats` use for their locale policy.
     */
    internal suspend fun report(locale: Locale) {
        val outcome = try {
            RecognitionLocale.check(
                locale = locale,
                alreadyNotified = appStateRepository.localeBoundaryNoticeShown.first(),
            )
        } catch (e: CancellationException) {
            throw e // structured concurrency — never swallowed by the fail-open catch (#909)
        } catch (t: Throwable) {
            // Fail-open: a flag/locale read failure must not surface as anything but a log line.
            Timber.tag(TAG).e(t, "Locale boundary check failed — skipping (#938)")
            return
        }

        if (outcome !is LocaleBoundaryCheck.Unsupported) return

        Timber.tag(TAG).w(
            "Device language '%s' is not '%s': recognition anchors and the text-marker privacy " +
                "scrubs are English-only today (#938), so recognition thins toward UNKNOWN and " +
                "the marker-based scrubs weaken. The id-based scrub layer is locale-immune.",
            outcome.language,
            RecognitionLocale.SUPPORTED_LANGUAGE,
        )

        if (!outcome.notifyUser) return

        try {
            postNotice()
            appStateRepository.setLocaleBoundaryNoticeShown()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Leave the flag unset so a later start retries the notice.
            Timber.tag(TAG).e(t, "Locale boundary notice could not be posted (#938)")
        }
    }

    private fun postNotice() {
        // The shared "App notices" channel (#937 moved the id + copy into AppNoticeChannel when
        // the recognition-health notice joined this family — one owner, principle 5).
        AppNoticeChannel.ensure(context, notificationManager)

        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = context.getString(R.string.locale_notice_text)
        val notification = NotificationCompat.Builder(context, AppNoticeChannel.ID)
            .setSmallIcon(R.drawable.bag_red_idle)
            .setContentTitle(context.getString(R.string.locale_notice_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "LocaleBoundary"

        /** This notice's id on the shared channel — see [AppNoticeChannel.Ids]. */
        const val NOTIFICATION_ID = AppNoticeChannel.Ids.LOCALE_BOUNDARY

        private const val REQUEST_CODE = 938
    }
}
