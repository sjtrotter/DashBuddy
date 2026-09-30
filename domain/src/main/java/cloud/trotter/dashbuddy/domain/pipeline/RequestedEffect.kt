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
    /**
     * #1149 review J3 — the bind-time label scan ([hintLabelsOf]) was COMPLETE (no slot-cap cut), so
     * [labelHintHashes] is the whole in-horizon set. Defaults false: a legacy journal/snapshot ref
     * (no field) loads as unprovable and never claims an exact fingerprint.
     */
    val labelHintsComplete: Boolean = false,
    /**
     * #1149 review L2 — the class of the bind's ACTION OWNER (whose region the hints fingerprint). The
     * 2b walk filters on it; [classNameHint] stays the bound node's class for strategies 1–3. Null means
     * the owner had no class (no filter, review N7); a legacy ref (null too) never reaches 2b because it
     * is never [labelHintsComplete].
     */
    val ownerClassHint: String? = null,
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

    /**
     * #1149 review J3 — the ONE owner of "this ref carries a provable exact fingerprint": hints
     * present and the bind-time set complete ([labelHintsComplete] — which since review P7 also
     * records that the set was not truncated at [MAX_LABEL_HINTS]; no size proxy). The executor gates strategy 2b on
     * it; an unprovable ref skips 2b for the bounds walk's containment check (the pre-#1149 shape).
     */
    val hasExactFingerprint: Boolean
        get() = labelHintHashes.isNotEmpty() && labelHintsComplete

    /** #1149 review P8 — the hint set, built once per ref (not per region the 2b walk checks). Not serialized (delegated). */
    private val hintSet: Set<String> by lazy { labelHintHashes.toHashSet() }

    /**
     * #1149 — the EXACT control fingerprint a label-only re-find (the executor's strategy 2b)
     * requires: the distinct hint hashes of [liveLabels] EQUAL this ref's hint set. Containment is
     * not enough there (the #1102 review's constraint 1): a clickable parent card holding the row
     * plus other text CONTAINS every hint, and with no geometric evidence nothing else would tell
     * the two apart. False unless [hasExactFingerprint]. The caller must
     * only pass a COMPLETE live scan (a budget-cut scan cannot prove "no extra label").
     */
    fun fingerprintMatches(liveLabels: List<String>): Boolean {
        if (!hasExactFingerprint) return false
        // #1149 review L7: cheap pre-check before any sha256 — the distinct letter-bearing keys must
        // number exactly the hints (the walk calls this for every clickable region).
        val keys = liveLabels.mapNotNullTo(HashSet(), ::hintKeyOrNull)
        if (keys.size != labelHintHashes.size) return false
        val live = keys.mapNotNullTo(HashSet()) { cloud.trotter.dashbuddy.domain.util.sha256OrNull(it) }
        return live == hintSet
    }

    /**
     * #1149 review J4/L7/R4 — could a PARTIALLY seen region still complete into this fingerprint? True
     * when it shows AT LEAST ONE letter-bearing key and all of its visible keys are in the ref's set.
     * An EMPTY visible set is no evidence at all (R4 — no vacuous veto: icon-only Compose regions would
     * otherwise veto every window); a hidden twin under an unreadable label-less region is the
     * accepted residual. Same cheap count pre-check before hashing as [fingerprintMatches].
     */
    fun visibleConsistentWith(visibleLabels: List<String>): Boolean {
        val keys = visibleLabels.mapNotNullTo(HashSet(), ::hintKeyOrNull)
        if (keys.isEmpty() || keys.size > labelHintHashes.size) return false
        // A hash failure is treated as CONSISTENT (the veto side — fail closed).
        return keys.all { k -> cloud.trotter.dashbuddy.domain.util.sha256OrNull(k)?.let { it in hintSet } ?: true }
    }

    companion object {
        const val MAX_LABEL_HINTS = 6
        const val MAX_LABEL_HINT_LENGTH = 40

        /**
         * #1149 review I2 — THE label horizon, one owner for both sides: the bind-time hint
         * collection ([hintLabelsOf], from `Ruleset.buildNodeRef`) and the fire-time live scan
         * (`UiInteractionHandler.scanLabels`) read a node's own labels plus its subtree down to
         * this depth, stopping at every clickable descendant (its labels are its own control's).
         */
        const val LABEL_SCAN_DEPTH = 3

        /** #1149 review I2 — the child fetches (bind time: child slots visited) one label scan may spend. */
        const val LABEL_SCAN_NODES = 24

        /** #1149 — the nodes an action-owner walk inspects: self + (MAX_OWNER_WALK − 1) parents, bind time and fire time (N4). */
        const val MAX_OWNER_WALK = 32

        /**
         * #1149 review L2 — the bind-time fingerprint is the ACTION OWNER's, like the fire-time one: the
         * bound node's nearest [UiNode.takesClick] self-or-ancestor (at most [MAX_OWNER_WALK] steps).
         * Its label region is hashed, its completeness recorded, and its class returned as the 2b
         * class filter. No owner, a foreign bound node or a foreign owner walk (N5) → REFUSED (R1): no
         * hints, never complete, and the caller emits no reference (the scan is skipped, S5).
         */
        fun bindHintsOf(bound: cloud.trotter.dashbuddy.domain.model.accessibility.UiNode): BindHints {
            // N5: a foreign node is never an owner and is never crossed. A bound node that is itself
            // foreign, or whose owner walk would reach/cross a foreign node, yields NO hints and is
            // never complete (no 2b) — the executor would never read or tap there anyway.
            val none = BindHints(emptyList(), complete = false, ownerClassHint = null, refused = true)
            if (bound.foreignPackage) return none
            var owner: cloud.trotter.dashbuddy.domain.model.accessibility.UiNode = bound
            var steps = 0
            // N4: self + (MAX_OWNER_WALK - 1) parents — exactly what the live resolveActionOwner inspects.
            while (!owner.takesClick && steps < MAX_OWNER_WALK - 1) {
                val parent = owner.parent ?: break
                if (parent.foreignPackage) return none
                owner = parent
                steps++
            }
            // S5: no owner → refused; skip the scan and hashing the caller would discard.
            val found = owner.takeIf { it.takesClick } ?: return none
            val scan = hintLabelsOf(found)
            val distinct = scan.labels.asSequence().mapNotNull(::hintHash).distinct().toList()
            // P7: truncation is RECORDED here, where it is known — a set cut at MAX_LABEL_HINTS is not
            // complete; an owner with exactly MAX_LABEL_HINTS labels is.
            return BindHints(
                distinct.take(MAX_LABEL_HINTS),
                complete = scan.complete && distinct.size <= MAX_LABEL_HINTS,
                ownerClassHint = found.className,
            )
        }

        /**
         * #1149 review I2/N8 — the bind-time label scan: [LabelHorizon.scan] (the ONE horizon rule the
         * executor's fire-time scans also run) over a mapped [UiNode] via [UiLabelNode] — takesClick
         * ownership (J2), foreign children never read (L3), unreadable / budget-dropped children make it
         * incomplete (L4/N6); the depth bound is the shared horizon.
         *
         * Residual (documented): a live null child spends a fetch slot, while a mapped [UiNode] carries
         * dropped children only as a count, so slot positions can differ — any drop makes the bind
         * incomplete anyway, so no exact fingerprint rests on that difference.
         */
        fun hintLabelsOf(node: cloud.trotter.dashbuddy.domain.model.accessibility.UiNode): LabelScan =
            LabelHorizon.scan(UiLabelNode(node)) // N8: the one horizon rule

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
/** #1149 review L2 — the bind-time fingerprint of the bound node's action owner ([NodeRef.bindHintsOf]). */
data class BindHints(
    val labelHintHashes: List<String>,
    val complete: Boolean,
    val ownerClassHint: String?,
    /**
     * #1149 review R1 — the bind is REFUSED (a foreign bound node or owner walk, or no owner at all):
     * `Ruleset.buildNodeRef` emits NO reference for it, so no strategy can tap anything for this bind —
     * it is an unresolved bind (the #1093 census).
     */
    val refused: Boolean = false,
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
