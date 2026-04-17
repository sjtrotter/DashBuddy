package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.domain.model.notification.NotificationInfo
import cloud.trotter.dashbuddy.domain.model.state.NotificationEvent
import javax.inject.Inject

class NotificationFactory @Inject constructor() {
    fun create(info: NotificationInfo): NotificationEvent {
        return NotificationEvent(
            timestamp = info.postTime,
            notification = info
        )
    }
}