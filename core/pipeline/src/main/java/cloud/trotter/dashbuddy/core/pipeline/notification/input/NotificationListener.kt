package cloud.trotter.dashbuddy.core.pipeline.notification.input

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.state.Platform
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class NotificationListener : NotificationListenerService() {

    @Inject
    lateinit var notificationSource: NotificationSource

    @Inject
    lateinit var platformPreferences: PlatformPreferences

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Cached set of enabled package names — updated reactively from preferences. */
    @Volatile
    private var enabledPackages: Set<String> = Platform.watchedPackages()

    override fun onListenerConnected() {
        Timber.i("Notification Listener Connected!")

        serviceScope.launch {
            platformPreferences.enabledPackages.collect { packages ->
                enabledPackages = packages
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName !in enabledPackages) return

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
        serviceScope.cancel()
    }
}
