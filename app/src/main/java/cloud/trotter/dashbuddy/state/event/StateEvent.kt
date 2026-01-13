package cloud.trotter.dashbuddy.state.event

sealed interface StateEvent {
    val timestamp: Long
}