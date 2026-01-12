package cloud.trotter.dashbuddy.statev2.event

sealed interface StateEvent {
    val timestamp: Long
}