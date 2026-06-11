package cloud.trotter.dashbuddy.domain.model.state

import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.Platform

data class TimeoutEvent(
    override val timestamp: Long = System.currentTimeMillis(),
    val type: TimeoutType = TimeoutType.SESSION_PAUSED_SAFETY,
    /** Platform region the timer belongs to — threads through to Observation.Timeout (#342). */
    val platform: Platform? = null,
    val payload: Map<String, Any?> = emptyMap(),
) : StateEvent
