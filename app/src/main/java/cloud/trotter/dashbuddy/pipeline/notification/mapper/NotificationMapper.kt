package cloud.trotter.dashbuddy.pipeline.notification.mapper

import android.service.notification.StatusBarNotification
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData

fun StatusBarNotification.toDomain(): RawNotificationData {
    val extras = this.notification.extras
    return RawNotificationData(
        packageName = this.packageName,
        title = extras.getString("android.title"),
        text = extras.getString("android.text"),
        bigText = extras.getString("android.bigText"),
        tickerText = this.notification.tickerText?.toString(),
        postTime = this.postTime,
        isClearable = this.isClearable
    )
}
