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
        // #731: the system rebinds this listener 129-240x/day per field observation — WARN (not
        // INFO) because a rebind opens an offer-miss window until reconnect, per the connect side
        // pairing with onListenerDisconnected below.
        val count = pipelineStats.onNotifListenerConnected()
        Timber.tag("Pipeline").w("Notification listener connected (count=%d this process)", count)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        // Shared StateFlow read (#356) — no per-service collector. The old
        // serviceScope was cancelled in onListenerDisconnected and reused on
        // rebind, so its re-launched collector was a no-op and package gating
        // froze at the last value for the process lifetime.
        if (sbn.packageName !in platformPreferences.enabledPackages.value) return

        Timber.d(
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
        // #731: WARN — a disconnect opens an offer-miss window until the system rebinds; the
        // running count quantifies the field-observed 129-240x/day flap.
        val count = pipelineStats.onNotifListenerDisconnected()
        Timber.tag("Pipeline").w("Notification listener disconnected (count=%d this process)", count)
    }
}
