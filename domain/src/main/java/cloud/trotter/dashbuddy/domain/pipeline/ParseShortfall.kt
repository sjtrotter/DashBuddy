package cloud.trotter.dashbuddy.domain.pipeline

/**
 * A rule MATCHED a frame while its declared parse came back with nothing usable (#1036).
 *
 * The failure this exists to name: DoorDash 8.93.7 removed the view ids every money parse
 * anchored on. The rules kept matching — their `require` blocks anchor on TEXT — every parse
 * died, and nothing in the pipeline could tell that apart from "matched, and there was nothing
 * to parse". It ran for weeks, and the frozen golden corpus structurally cannot see it (#1029:
 * the corpus is the old UI, where the ids resolve).
 *
 * Two independent triggers, either or both:
 *  - [allNullFieldCount] — EVERY field the parse block declares as evidence about this frame
 *    resolved to nothing (null, or an empty collection). Total rot.
 *  - [nullRequiredFields] — a field the parse's own SHAPE contract names as load-bearing
 *    (`ParsedFieldsFactory.REQUIRED_FIELDS_BY_SHAPE`, e.g. `post_task.totalPay`) came back
 *    null while other fields still parsed. PARTIAL rot — which is what the 8.93.7 receipt
 *    looked like one release before it became total.
 *
 * A third trigger joined in #1093, one block over from the parse: [unresolvedOptionalBindings]
 * — a `bind` target the rule declares `optional` that resolved NO node on a frame the rule
 * matched. The receipt's `expandButton` anchored on `expandable_view`, 8.93.7 removed the id,
 * and because the bind was optional the EXPAND_EARNINGS tap was simply never emitted — no
 * WARN, no count, for weeks. An optional bind that never resolves is indistinguishable from a
 * healthy one without this. Reported by BIND NAME (ours), never by node content (P7).
 *
 * Diagnostic ONLY. Nothing reads it to decide anything: the observation carrying it is built
 * exactly as it would be without it, and the state machine never sees it.
 */
data class ParseShortfall(
    /** The rule that matched. Our own authored identifier — never third-party text (P7). */
    val ruleId: String,
    /** How many evidence fields were declared, when ALL of them came back empty; 0 otherwise. */
    val allNullFieldCount: Int = 0,
    /** Shape-required fields that resolved null; empty when the shape declares none or all resolved. */
    val nullRequiredFields: List<String> = emptyList(),
    /** Optional `bind` targets that resolved no node on this matched frame (#1093); sorted, by name. */
    val unresolvedOptionalBindings: List<String> = emptyList(),
    /**
     * #1149 review R7 — ACTION-target binds (`RuleAction.byTargetBindName`) that resolved a node whose
     * bind-time fingerprint is UNPROVABLE (`NodeRef.labelHintsComplete == false`: unreadable children,
     * more than `MAX_LABEL_HINTS` labels, or no letter-bearing label at all — S7). The tap still has strategies 1/2/3, but never 2b; counted so
     * the loss is visible. Sorted, by name (ours).
     */
    val unprovableBindings: List<String> = emptyList(),
    /** #1149 review S7 — why each [unprovableBindings] entry is unprovable (our wording, no node content). */
    val unprovableReasons: Map<String, String> = emptyMap(),
    /**
     * #1149 review S4 — ACTION-target binds that resolved a node but were REFUSED (R1: a foreign bound
     * node or owner walk, or no clickable owner at all): no reference was emitted, the target is
     * withheld. Its own truth, NOT folded into [unresolvedOptionalBindings] (it did resolve a node, and
     * may be mandatory). Sorted, by name (ours).
     */
    val refusedBindings: List<String> = emptyList(),
) {
    /** Either PARSE trigger fired (the #1036 pair) — the census/WARN keyed by rule id. */
    val hasParseTrigger: Boolean get() = allNullFieldCount > 0 || nullRequiredFields.isNotEmpty()

    /** True when no trigger fired — parse and binds are healthy and nothing should be reported. */
    val isEmpty: Boolean get() = !hasParseTrigger && unresolvedOptionalBindings.isEmpty() &&
        unprovableBindings.isEmpty() && refusedBindings.isEmpty()
}
