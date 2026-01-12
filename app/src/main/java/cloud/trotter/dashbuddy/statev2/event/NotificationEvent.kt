package cloud.trotter.dashbuddy.statev2.event

import cloud.trotter.dashbuddy.statev2.model.NotificationInfo

data class NotificationEvent(
    override val timestamp: Long,
    val notification: NotificationInfo
) : StateEvent