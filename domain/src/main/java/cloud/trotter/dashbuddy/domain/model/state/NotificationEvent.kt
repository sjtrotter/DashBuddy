package cloud.trotter.dashbuddy.domain.model.state

import cloud.trotter.dashbuddy.domain.model.notification.NotificationInfo

data class NotificationEvent(
    override val timestamp: Long,
    val info: NotificationInfo,
) : StateEvent
