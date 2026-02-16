package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.state.event.NotificationEvent
import javax.inject.Inject

class NotificationFactory @Inject constructor() {
    fun create(info: NotificationInfo): NotificationEvent {
        return NotificationEvent(
            timestamp = info.timestamp,
            notification = info
        )
    }
}