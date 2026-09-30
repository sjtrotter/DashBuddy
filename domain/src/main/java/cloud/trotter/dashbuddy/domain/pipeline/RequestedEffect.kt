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
     * present, the bind-time scan complete ([labelHintsComplete]), and the set below
     * [MAX_LABEL_HINTS] (at the cap it may have been truncated). The executor gates strategy 2b on
     * it; an unprovable ref skips 2b for the bounds walk's containment check (the pre-#1149 shape).
     */
    val hasExactFingerprint: Boolean
        get() = labelHintHashes.isNotEmpty() && labelHintsComplete && labelHintHashes.size < MAX_LABEL_HINTS

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
        return live == labelHintHashes.toHashSet()
    }

    /**
     * #1149 review J4/L7 — could a PARTIALLY seen region still complete into this fingerprint? True
     * when its visible distinct hint set is a subset of the ref's (the empty set included). Same
     * cheap count pre-check before hashing as [fingerprintMatches].
     */
    fun visibleConsistentWith(visibleLabels: List<String>): Boolean {
        val keys = visibleLabels.mapNotNullTo(HashSet(), ::hintKeyOrNull)
        if (keys.size > labelHintHashes.size) return false
        val hints = labelHintHashes.toHashSet()
        // A hash failure is treated as CONSISTENT (the veto side — fail closed).
        return keys.all { k -> cloud.trotter.dashbuddy.domain.util.sha256OrNull(k)?.let { it in hints } ?: true }
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
         * class filter. No owner → hints from the bound node, never complete (no 2b). A foreign bound
         * node or owner walk (review N5) → no hints, never complete.
         */
        fun bindHintsOf(bound: cloud.trotter.dashbuddy.domain.model.accessibility.UiNode): BindHints {
            // N5: a foreign node is never an owner and is never crossed. A bound node that is itself
            // foreign, or whose owner walk would reach/cross a foreign node, yields NO hints and is
            // never complete (no 2b) — the executor would never read or tap there anyway.
            val none = BindHints(emptyList(), complete = false, ownerClassHint = null)
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
            val found = owner.takeIf { it.takesClick }
            val scan = hintLabelsOf(found ?: bound)
            val hashes = scan.labels.asSequence().mapNotNull(::hintHash).distinct().take(MAX_LABEL_HINTS).toList()
            return BindHints(hashes, complete = found != null && scan.complete, ownerClassHint = found?.className)
        }

        /**
         * #1149 review I2 — the bind-time mirror of the executor's live label scan over a mapped
         * [UiNode]: own text/contentDescription, then children depth-first in pre-order (the same order as the
         * executor's `scanLabels`) down to
         * [LABEL_SCAN_DEPTH], at most [LABEL_SCAN_NODES] child slots, never descending into a
         * descendant that [UiNode.takesClick] (review J2 — the live `isActionClickable`'s mirror),
         * never reading a [UiNode.foreignPackage] child (L3), and incomplete when an in-horizon node
         * reports [UiNode.unreadableChildren] (L4) — both mirrors of the live scan. [UiLabelScan.complete] is false only when the slot cap cut it — the
         * depth bound is the shared HORIZON (labels below it belong to neither side's fingerprint).
         *
         * Residual (documented): fire time budgets FETCH attempts (a null child spends one), while a
         * mapped [UiNode] has already dropped null children, so a live window with null slots can
         * reach its cap sooner. (The former "clickable means isClickable only at bind time" residual
         * is closed by [UiNode.hasClickAction], review J2.)
         */
        fun hintLabelsOf(node: cloud.trotter.dashbuddy.domain.model.accessibility.UiNode): UiLabelScan {
            val labels = mutableListOf<String>()
            var fetched = 0
            var complete = true
            fun visit(n: cloud.trotter.dashbuddy.domain.model.accessibility.UiNode, depth: Int): Boolean {
                n.text?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
                n.contentDescription?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
                if (depth >= LABEL_SCAN_DEPTH) return true // the horizon, not a cut
                // L4: an advertised child the mapper could not read is an in-horizon label left unseen.
                if (n.unreadableChildren > 0) complete = false
                for (child in n.children) {
                    if (fetched >= LABEL_SCAN_NODES) { complete = false; return false }
                    fetched++
                    // L3: an embedded foreign-package child spends its slot but is never read — the
                    // executor's scanLabels skips it the same way.
                    if (child.foreignPackage) continue
                    if (child.takesClick) continue // J2: the same predicate as the live isActionClickable
                    if (!visit(child, depth + 1)) return false
                }
                return true
            }
            visit(node, 0)
            return UiLabelScan(labels, complete)
        }

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
data class BindHints(val labelHintHashes: List<String>, val complete: Boolean, val ownerClassHint: String?)

/** #1149 review I2 — a bounded bind-time label scan ([NodeRef.hintLabelsOf]); [complete] = the slot cap did not cut it. */
data class UiLabelScan(val labels: List<String>, val complete: Boolean)

/**
 * Gate condition evaluated against parsed fields to decide whether
 * an effect should fire.
 */
sealed class ParsedFieldsGate {
    data class FieldEquals(val field: String, val value: Any?) : ParsedFieldsGate()
    data class FieldNotEquals(val field: String, val value: Any?) : ParsedFieldsGate()
    data class FieldNotNull(val field: String) : ParsedFieldsGate()
}
