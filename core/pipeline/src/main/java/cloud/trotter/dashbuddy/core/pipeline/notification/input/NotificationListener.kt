package cloud.trotter.dashbuddy.core.pipeline.notification.input

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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

    override fun onListenerConnected() {
        Timber.i("Notification Listener Connected!")
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
        Timber.i("Notification Listener Disconnected.")
    }
}
