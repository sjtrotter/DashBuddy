package cloud.trotter.dashbuddy.domain.model.order

/**
 * How a shop order's count on the offer card is *denominated* (#823 Phase 1).
 *
 * DoorDash renders a Shop & Deliver order's quantity three ways — `(9 items • 11 units)`,
 * `(4 items)`, and sometimes **units-only** `(64 units)`. The parse's item-count read grabs the
 * first number regardless of its label ([cloud.trotter.dashbuddy.core-pipeline `parseItemCount`]),
 * so a units-only offer silently feeds a *unit* count into the #556 shop-time model as if it were an
 * item count — and units are not items (a 64-unit H-E-B basket is far fewer physical items, corpus
 * pairs cluster items:units ≈ 0.75–0.81). This enum records which label the parsed count came from
 * so the evaluator can convert a units-denominated count into an items-equivalent before the pace
 * divide (see `OfferEvaluator` + `UserEconomy.effectiveItemsPerUnitRatio`).
 *
 * Recognition is **data, not code** (CLAUDE.md): the platform ruleset emits a parse-output string
 * that `ParsedFieldsFactory` resolves **fail-neutrally** — [UNITS] only on an exact `== "UNITS"`
 * match (paired with a real, non-estimated count), everything else (including an unrecognized
 * string) falls to [ITEMS]; there is no `valueOf` throw and no `when (platform)`. [ITEMS] is the
 * neutral default — an items-denominated or estimated/absent count behaves exactly as before, so
 * only the units-only shape changes anything.
 */
enum class CountUnit {
    /** The count is a physical **item** count (`(4 items)`, or the items figure of `(9 items • 11 units)`). */
    ITEMS,

    /** The count is a **unit** count with no items figure present (`(64 units)`). */
    UNITS,
}
