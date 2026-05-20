package cloud.trotter.dashbuddy.domain.model.state

import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType

data class TimeoutEvent(
    override val timestamp: Long = System.currentTimeMillis(),
    val type: TimeoutType = TimeoutType.SESSION_PAUSED_SAFETY,
    val payload: Map<String, Any?> = emptyMap(),
) : StateEvent