package cloud.trotter.dashbuddy.pipeline.notification.mapper

import android.service.notification.StatusBarNotification
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData

fun StatusBarNotification.toDomain(): RawNotificationData {
    val extras = this.notification.extras
    return RawNotificationData(
        packageName = this.packageName,
        title = extras.getCharSequence("android.title")?.toString(),
        text = extras.getCharSequence("android.text")?.toString(),
        bigText = extras.getCharSequence("android.bigText")?.toString(),
        tickerText = this.notification.tickerText?.toString(),
        subText = extras.getCharSequence("android.subText")?.toString(),
        postTime = this.postTime,
        isClearable = this.isClearable,
        isOngoing = this.isOngoing,
        category = this.notification.category,
        channelId = this.notification.channelId,
        actionLabels = this.notification.actions
            ?.mapNotNull { it.title?.toString() } ?: emptyList(),
    )
}
