package cloud.trotter.dashbuddy.domain.pipeline

/**
 * A UI action requested by a rule's `actions:` block.
 *
 * Actions ride on [Observation.FlowObservation] through the state machine
 * (which treats them as opaque) and are emitted as effects by [EffectMap].
 * The side-effect engine resolves the [targetRef] against the live UI tree
 * and executes the verb.
 *
 * See ADR-0006 for the full design.
 */
data class RequestedAction(
    val verb: String,
    val targetRef: NodeRef,
    val onlyIf: ParsedFieldsGate?,
    val dedupeKey: String?,
    val throttleMs: Long?,
    val ruleId: String,
)

/**
 * A content fingerprint of a UI node captured at match time.
 *
 * Never a live node reference — the side-effect engine resolves this
 * against the current accessibility tree when executing the action.
 */
data class NodeRef(
    val viewIdSuffix: String?,
    val text: String?,
    val classNameHint: String?,
    val pathFingerprint: String,
)

/**
 * Gate condition evaluated against parsed fields to decide whether
 * an action should fire.
 */
sealed class ParsedFieldsGate {
    data class FieldEquals(val field: String, val value: Any?) : ParsedFieldsGate()
    data class FieldNotEquals(val field: String, val value: Any?) : ParsedFieldsGate()
    data class FieldNotNull(val field: String) : ParsedFieldsGate()
}
