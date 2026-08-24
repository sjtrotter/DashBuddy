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
) {
    /** True when neither trigger fired — the parse is healthy and nothing should be reported. */
    val isEmpty: Boolean get() = allNullFieldCount == 0 && nullRequiredFields.isEmpty()
}
