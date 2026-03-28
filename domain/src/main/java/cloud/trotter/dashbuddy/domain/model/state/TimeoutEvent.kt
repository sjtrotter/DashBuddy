package cloud.trotter.dashbuddy.domain.model.state

data class TimeoutEvent(
    override val timestamp: Long = System.currentTimeMillis(),
    val type: TimeoutType = TimeoutType.DASH_PAUSED_SAFETY
) : StateEvent