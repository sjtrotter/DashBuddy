package cloud.trotter.dashbuddy.domain.model.state

/**
 * Common event interface for the state machine. Implemented by both
 * legacy event types (ScreenUpdateEvent, ClickEvent, etc.) and the
 * new [cloud.trotter.dashbuddy.domain.pipeline.Observation] hierarchy.
 *
 * Non-sealed so that Observation (in domain.pipeline) can extend it.
 */
interface StateEvent {
    val timestamp: Long
}