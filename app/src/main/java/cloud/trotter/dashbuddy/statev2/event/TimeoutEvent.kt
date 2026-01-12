package cloud.trotter.dashbuddy.statev2.event

data class TimeoutEvent(
    override val timestamp: Long,
    val type: String // e.g. "DashPause", "AutoDecline"
) : StateEvent