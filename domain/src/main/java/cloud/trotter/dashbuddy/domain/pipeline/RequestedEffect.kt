package cloud.trotter.dashbuddy.domain.pipeline

import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox

/**
 * A side effect requested by a rule's `effects:` block.
 *
 * Effects ride on [Observation.FlowObservation] through the state machine
 * (which treats them as opaque) and are emitted as [AppEffect]s by EffectMap.
 * The side-effect engine resolves the verb and executes it.
 *
 * Rule effects are purely observational/app-internal (#425): actuation left
 * this surface — rulesets expose target *bindings* (see
 * `Observation.FlowObservation.targets`) and the app-owned `RuleAction`
 * registry decides and performs taps.
 *
 * See ADR-0006 for the original actions design; this generalises it to
 * the full [EffectVerb] vocabulary.
 */
data class RequestedEffect(
    val verb: EffectVerb,
    val args: Map<String, String> = emptyMap(),
    val onlyIf: ParsedFieldsGate? = null,
    val dedupeKey: String? = null,
    val throttleMs: Long? = null,
    val ruleId: String,
)

/**
 * A content fingerprint of a UI node captured at match time.
 *
 * Never a live node reference — the action executor re-resolves this
 * against the current accessibility tree (package-scoped, label-verified;
 * #425) when performing a tap. Carries as many differentiating clues as
 * possible (ID, text, bounds, class name, structural path) so the executor
 * can reliably find the node even on screens where some identifiers are
 * absent.
 */
@kotlinx.serialization.Serializable
data class NodeRef(
    val viewIdSuffix: String?,
    val text: String?,
    val classNameHint: String?,
    val boundsInScreen: BoundingBox,
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
