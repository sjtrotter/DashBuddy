package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.domain.model.notification.NotificationInfo
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.model.state.NotificationEvent
import javax.inject.Inject

class NotificationFactory @Inject constructor() {
    fun create(raw: RawNotificationData, info: NotificationInfo): NotificationEvent =
        NotificationEvent(
            timestamp = raw.postTime,
            info = info,
        )
}
