package cloud.trotter.dashbuddy.domain.model.state

import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload

data class TimeoutEvent(
    /** Explicit (#366): the engine stamps the fire time at the edge. */
    override val timestamp: Long,
    /** Required (#439): no default, so a missing type can't masquerade as a pause expiry. */
    val type: TimeoutType,
    /** Platform region the timer belongs to — threads through to Observation.Timeout (#342). */
    val platform: Platform? = null,
    val payload: ObservationPayload? = null,
) : StateEvent
