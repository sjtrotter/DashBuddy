package cloud.trotter.dashbuddy.domain.pipeline

/**
 * Semantic identity of an observation for post-classification dedup.
 *
 * Two observations with the same identity represent the same thing
 * (same screen with same data, same notification, etc.). Used to
 * suppress duplicate captures and state machine events.
 */
data class ObservationIdentity(
    val type: String,
    val target: String?,
    val fieldsHash: Int,
)

/**
 * Compute the semantic identity of this observation.
 *
 * The identity is based on the observation type, classification target,
 * and a hash of the stable parsed fields (excluding transient values
 * like deadlines and timestamps).
 */
fun Observation.identity(): ObservationIdentity = when (this) {
    is Observation.Screen -> ObservationIdentity("screen", target, parsed.dedupeHash())
    is Observation.Click -> ObservationIdentity("click", target, parsed.dedupeHash())
    is Observation.Notification -> ObservationIdentity("notification", target, parsed.dedupeHash())
    is Observation.Timeout -> ObservationIdentity("timeout", type.name, 0)
    is Observation.UiInput -> ObservationIdentity("ui_input", action, payload.hashCode())
    is Observation.Loopback -> ObservationIdentity("loopback", effect, payload.hashCode())
}
