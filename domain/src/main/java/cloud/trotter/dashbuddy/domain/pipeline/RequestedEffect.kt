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
     * #1093 — sha256s of the bound node's SUBTREE labels at bind time (text + contentDescription,
     * letter-bearing only, normalized by [hintKeyOrNull]; at most [MAX_LABEL_HINTS]). A
     * bounds-derived candidate at fire time — the only way an id-less, text-less container is
     * re-found after the sheet slid — must carry EVERY one of these among its own live labels
     * ([agreesWithLabels]): geometry alone cannot verify a label-free action (an animating
     * receipt captured 400 px low would hand the tap to "Continue dashing"; a control at the
     * exact captured rect would too), and ONE shared label is not identity either (a figure like
     * `$40.57` repeats across a receipt, so numeric labels are never hints). Hashes, never text:
     * this ref rides `ObservationPayload.DeferredAction` into the journal and snapshots, and a
     * subtree label can be anything the platform renders (Pledge — nothing raw is persisted).
     */
    val labelHintHashes: List<String> = emptyList(),
) {
    /**
     * True when EVERY hint is present among [liveLabels] (normalized + hashed the same way).
     * False on no hints — a ref without hints carries no identity evidence for a bounds match.
     */
    fun agreesWithLabels(liveLabels: List<String>): Boolean {
        if (labelHintHashes.isEmpty()) return false
        val live = liveLabels.mapNotNull(::hintHash).toHashSet()
        return labelHintHashes.all { it in live }
    }

    companion object {
        const val MAX_LABEL_HINTS = 6
        const val MAX_LABEL_HINT_LENGTH = 40

        /**
         * The ONE normalization both sides use: trimmed, clamped, lower-cased (ROOT); null for a
         * label with no letter at all (a bare amount, a count, a spacer) — those repeat across a
         * surface and would let a stranger "agree".
         */
        fun hintKeyOrNull(label: String): String? {
            val key = label.trim().take(MAX_LABEL_HINT_LENGTH).lowercase(java.util.Locale.ROOT)
            return key.takeIf { k -> k.any { it.isLetter() } }
        }

        /** sha256 of [hintKeyOrNull]; null when the label carries no key (fail-closed: no hint). */
        fun hintHash(label: String): String? =
            hintKeyOrNull(label)?.let { cloud.trotter.dashbuddy.domain.util.sha256OrNull(it) }
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
