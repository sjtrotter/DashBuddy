package cloud.trotter.dashbuddy.domain.pipeline

/**
 * A side effect requested by a rule's `effects:` block.
 *
 * Effects ride on [Observation.FlowObservation] through the state machine
 * (which treats them as opaque) and are emitted as [AppEffect]s by EffectMap.
 * The side-effect engine resolves the verb and executes it, optionally
 * using [targetRef] for UI-targeted verbs like [EffectVerb.CLICK].
 *
 * See ADR-0006 for the original actions design; this generalises it to
 * the full [EffectVerb] vocabulary.
 */
data class RequestedEffect(
    val verb: EffectVerb,
    val args: Map<String, String> = emptyMap(),
    val targetRef: NodeRef? = null,
    val onlyIf: ParsedFieldsGate? = null,
    val dedupeKey: String? = null,
    val throttleMs: Long? = null,
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
 * an effect should fire.
 */
sealed class ParsedFieldsGate {
    data class FieldEquals(val field: String, val value: Any?) : ParsedFieldsGate()
    data class FieldNotEquals(val field: String, val value: Any?) : ParsedFieldsGate()
    data class FieldNotNull(val field: String) : ParsedFieldsGate()
}
