package cloud.trotter.dashbuddy.pipeline.inputs

import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.log.Logger
import cloud.trotter.dashbuddy.pipeline.Pipeline
import cloud.trotter.dashbuddy.state.model.NotificationInfo
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class NotificationListener : NotificationListenerService() {

    @Inject
    lateinit var pipeline: Pipeline

    private val tag = "NotificationWatcher"

    override fun onListenerConnected() {
        Logger.i(tag, "Notification Listener Connected!")
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // 1. Filter: Only care about specific apps?
        val packageName = sbn.packageName
        val validPackages = setOf("com.doordash.driverapp")

        if (packageName !in validPackages) return

        // 2. Parse: Extract richer data available in StatusBarNotification
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString()

        Logger.d(tag, "Notification from $packageName: $title | $text")

        // 3. Map to your existing NotificationInfo object
        val info = NotificationInfo(
            title = title,
            text = text,
            bigText = bigText,
            packageName = packageName,
            timestamp = sbn.postTime
        )

        // 4. Input to pipeline
        pipeline.onNotificationPosted(info)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optional: Handle when a notification is dismissed
    }
}