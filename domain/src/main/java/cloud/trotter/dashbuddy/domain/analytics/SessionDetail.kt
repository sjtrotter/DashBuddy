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
    /** Σ captured delivery pay for THIS session (null pays counted as 0). */
    val deliveredPay: Double get() = deliveries.sumOf { it.realizedPay ?: 0.0 }

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
}
