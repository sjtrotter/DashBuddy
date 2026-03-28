package cloud.trotter.dashbuddy.domain.model.state

sealed interface StateEvent {
    val timestamp: Long
}