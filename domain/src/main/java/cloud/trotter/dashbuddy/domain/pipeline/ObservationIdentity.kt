package cloud.trotter.dashbuddy.domain.pipeline

import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields

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
    val modeHint: Mode? = null,
)

/**
 * Compute the semantic identity of this observation.
 *
 * The identity is based on the observation type, classification target,
 * a hash of the stable parsed fields (excluding transient values
 * like deadlines and timestamps), and the mode hint. Including modeHint
 * ensures that a screen whose UI changes between online/offline states
 * is not suppressed as a duplicate.
 */
/**
 * Null = never dedup (#366): clicks with parsed ClickFields are always unique —
 * previously expressed as a nanoTime-salted hash, now an explicit signal.
 */
fun Observation.identity(): ObservationIdentity? = when (this) {
    is Observation.Screen -> ObservationIdentity("screen", target, parsed.dedupeHash(), modeHint)
    is Observation.Click ->
        if (parsed is ParsedFields.ClickFields) null
        else ObservationIdentity("click", target, parsed.dedupeHash(), modeHint)
    is Observation.Notification -> ObservationIdentity("notification", target, parsed.dedupeHash(), modeHint)
    is Observation.Timeout -> ObservationIdentity("timeout", type.name, 0)
    is Observation.UiInput -> ObservationIdentity("ui_input", action, 0)
    is Observation.Loopback -> ObservationIdentity("loopback", effect, payload.hashCode())
}
