package cloud.trotter.dashbuddy.state.event

import cloud.trotter.dashbuddy.pipeline.model.NotificationInfo

data class NotificationEvent(
    override val timestamp: Long,
    val notification: NotificationInfo
) : StateEvent