package cloud.trotter.dashbuddy.core.pipeline.rules

/**
 * The ONE definition of "a well-formed currency figure" (#1029).
 *
 * Two places had to agree on it and did not: the `parseGlyphCurrency` transform's settled-shape
 * full match (Kotlin) and the `nextSiblingMatchingRegex(...)` money scans in the DoorDash receipt
 * rules (JSON5). Both were hand-written and both were loose in different ways — the Kotlin one
 * accepted a leading-zero integer (`$016.70`, one settled digit away from the fielded mid-spin
 * `$016.603`) and a malformed comma group (`$1234,567.00` → 1234567.0); the rule-side
 * `^\$[\d,]+\.\d{2}$` accepted `$,.00` (→ 0.0) and `$1,2,3.45`.
 *
 * So the shape is stated once, here, and both sides derive from it: the transform composes
 * [FIGURE_CORE] with a literal `$` and full-matches, the rules declare [RULE_PATTERN] verbatim, and
 * an `:app` test byte-pins every `nextSiblingMatchingRegex` pattern in the generated assets against
 * it — the `SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN` precedent, where a shared shape string is
 * pinned rather than trusted to stay in sync.
 */
object CurrencyShape {

    /**
     * The figure itself, without the currency symbol: either a bare `0`, or 1–4 digits with no
     * leading zero, or a single well-formed thousands group — then exactly two fraction digits.
     *
     * Four digits is the ceiling on purpose: this reads a per-delivery total or a dash/week
     * running total, and a five-figure one of either is a mis-aimed rule, not a windfall.
     */
    const val FIGURE_CORE: String = "(?:0|[1-9]\\d{0,3}|[1-9]\\d{0,2},\\d{3})\\.\\d{2}"

    /**
     * The anchored, `$`-prefixed pattern a RULE declares (JSON5-escaped at the call site). Byte-
     * pinned by `CurrencyShapePinTest` against every money scan in the generated rulesets.
     */
    const val RULE_PATTERN: String = "^\\\$" + FIGURE_CORE + "\$"
}
