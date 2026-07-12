package cloud.trotter.dashbuddy.core.pipeline.notification.input

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class NotificationListener : NotificationListenerService() {

    @Inject
    lateinit var notificationSource: NotificationSource

    @Inject
    lateinit var platformPreferences: PlatformPreferences

    @Inject
    lateinit var pipelineStats: PipelineStats

    override fun onListenerConnected() {
        // #731: the connect is the RECOVERY half of a rebind cycle (and fires once at every normal
        // startup), so it stays INFO; the degradation signal is the disconnect WARN below. The
        // count still rides the exportable INFO+ slice for desk analysis of the flap rate.
        val count = pipelineStats.onNotifListenerConnected()
        Timber.tag("Pipeline").i("Notification listener connected (count=%d this process)", count)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        // Shared StateFlow read (#356) — no per-service collector. The old
        // serviceScope was cancelled in onListenerDisconnected and reused on
        // rebind, so its re-launched collector was a no-op and package gating
        // froze at the last value for the process lifetime.
        if (sbn.packageName !in platformPreferences.enabledPackages.value) return

        Timber.tag("Pipeline").d(
            "\uD83D\uDCEC Notification from %s: clearable=%s ongoing=%s channel=%s",
            sbn.packageName, sbn.isClearable, sbn.isOngoing, sbn.notification.channelId,
        )
        notificationSource.emit(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optional: Handle when a notification is dismissed
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // #731: a disconnect opens an offer-miss window until the system rebinds — a genuine
        // degradation, but field-observed at 129-240x/day, which would drown the exportable WARN
        // slice (Principle 7's "must not be drowned" clause; ~1.3k WARNs on the whole 06-19 dash).
        // Edge-gate like the D6 join-miss WARN: the FIRST disconnect per process announces the
        // degradation class at WARN; the rest ride INFO (still exported) with the running count,
        // which is what the desk flap analysis actually consumes.
        val count = pipelineStats.onNotifListenerDisconnected()
        if (count == 1L) {
            Timber.tag("Pipeline").w("Notification listener disconnected (count=%d this process)", count)
        } else {
            Timber.tag("Pipeline").i("Notification listener disconnected (count=%d this process)", count)
        }
    }
}
