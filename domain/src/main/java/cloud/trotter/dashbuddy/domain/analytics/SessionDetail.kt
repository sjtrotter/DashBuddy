package cloud.trotter.dashbuddy.domain.analytics

/**
 * One dash, fully expanded — the read-only per-dash drill-down (#650 PR A): the [session] header
 * plus every [DeliveryRecord] captured under it, in completion order. Read-model only, assembled at
 * the repository boundary from the durable records; no economy dependency (the per-delivery net is
 * the frozen stored value, never re-costed), no new PII surface (store names are driver-owned;
 * customer/address hashes are not exposed on [DeliveryRecord] at all).
 *
 * Corrections/edits are #650 PR B — this DTO is the surface those correction entry points hang off,
 * but here it is strictly a read projection.
 */
data class SessionDetail(
    val session: SessionRecord,
    val deliveries: List<DeliveryRecord>,   // completion order
) {
    /**
     * Σ captured delivery pay for THIS session (null pays counted as 0). Deliberately CASH-FREE
     * (#688 locked accounting): cash tips live outside `realizedPay`, so this stays the exact
     * quantity the `unattributedPay` reconciliation compares against `reportedEarnings`.
     */
    val deliveredPay: Double get() = deliveries.sumOf { it.realizedPay ?: 0.0 }

    /**
     * Σ driver-entered cash tips for THIS session (#688). Added to the displayed gross/net as its
     * OWN line — never folded into [deliveredPay] or [unattributedPay], so recording a cash tip
     * raises gross/net without shrinking the reported-vs-captured reconciliation.
     */
    val cashTips: Double get() = deliveries.sumOf { it.cashTip ?: 0.0 }

    /**
     * `reported − delivered` when the platform-reported total exceeds captured delivery pay, else
     * `0` (never negative) — the per-dash review flag. This deliberately mirrors the SQL `CASE` in
     * [cloud.trotter.dashbuddy.domain.analytics] `AnalyticsDao.grossAndUnattributed` (that query is
     * the definition owner: reported-authoritative, non-negative); this is the same number the
     * period-level unattributed callout sums, scoped to one dash. It is the entry point the #650 PR B
     * corrections attach to.
     */
    val unattributedPay: Double
        get() {
            val reported = session.reportedEarnings ?: return 0.0
            return (reported - deliveredPay).coerceAtLeast(0.0)
        }

    /**
     * `delivered − reported` when captured delivery pay exceeds the platform-reported total, else
     * `0` (never negative) — the mirror of [unattributedPay] (#701), a **display-only** per-dash
     * review signal for an over-count (never folded into [unattributedPay] or any net figure). Same
     * cash-free [deliveredPay] comparison as [unattributedPay], so the #688 cash exclusion holds for
     * both directions; `0` when [session] carries no `reportedEarnings` (nothing to compare against).
     */
    val overAttributedPay: Double
        get() {
            val reported = session.reportedEarnings ?: return 0.0
            return (deliveredPay - reported).coerceAtLeast(0.0)
        }
}
