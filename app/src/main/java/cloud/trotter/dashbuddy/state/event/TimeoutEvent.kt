package cloud.trotter.dashbuddy.state.event

import cloud.trotter.dashbuddy.state.model.TimeoutType

data class TimeoutEvent(
    override val timestamp: Long,
    val type: TimeoutType = TimeoutType.DASH_PAUSED_SAFETY
) : StateEvent