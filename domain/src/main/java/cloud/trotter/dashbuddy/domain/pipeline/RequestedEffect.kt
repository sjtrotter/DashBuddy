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
    /**
     * #1093 — the first few text/contentDescription entries of the bound node's SUBTREE at bind
     * time (bounded: [MAX_LABEL_HINTS] × [MAX_LABEL_HINT_LENGTH] chars). A RELAXED bounds-walk
     * candidate at fire time — a clickable same-class node that merely overlaps the captured rect,
     * which is how an id-less, text-less container is re-found after the sheet slid — must share
     * at least one of these with its own live labels ([agreesWithLabels]); geometry alone cannot
     * verify a label-free action (an animating receipt captured 400 px low would otherwise hand
     * the tap to "Continue dashing"). An exact class+bounds match needs no hint. Same exposure
     * class as [text] (the node's own text already rides here); nothing is logged from it.
     */
    val labelHints: List<String> = emptyList(),
) {
    /** True when [liveLabels] shares a normalized entry with [labelHints]; false on no hints. */
    fun agreesWithLabels(liveLabels: List<String>): Boolean {
        if (labelHints.isEmpty()) return false
        val keys = labelHints.map(::labelKey).toHashSet()
        return liveLabels.any { labelKey(it) in keys }
    }

    companion object {
        const val MAX_LABEL_HINTS = 6
        const val MAX_LABEL_HINT_LENGTH = 40

        /** The ONE normalization both sides of [agreesWithLabels] use (bind time and fire time). */
        fun labelKey(label: String): String =
            label.trim().take(MAX_LABEL_HINT_LENGTH).lowercase(java.util.Locale.ROOT)
    }
}

/**
 * Gate condition evaluated against parsed fields to decide whether
 * an effect should fire.
 */
sealed class ParsedFieldsGate {
    data class FieldEquals(val field: String, val value: Any?) : ParsedFieldsGate()
    data class FieldNotEquals(val field: String, val value: Any?) : ParsedFieldsGate()
    data class FieldNotNull(val field: String) : ParsedFieldsGate()
}
