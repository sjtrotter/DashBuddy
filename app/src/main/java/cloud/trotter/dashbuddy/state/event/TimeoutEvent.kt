package cloud.trotter.dashbuddy.state.event

data class TimeoutEvent(
    override val timestamp: Long,
    val type: String // e.g. "DashPause", "AutoDecline"
) : StateEvent