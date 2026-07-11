package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.state.Platform

/**
 * Domain read-models for the durable analytics records (#314) — the repository
 * boundary maps the Room entities into these so consumers never see persistence
 * types (the `AppEventRepo.toDomain` convention). Platform is registry-resolved,
 * never a raw wire string (Principle 8).
 *
 * These back the future per-dash drill-down (#650); nothing renders them yet, so
 * they carry the economically meaningful fields a "recent dashes" list and a
 * single-dash breakdown need, no more.
 */

/** One completed delivery. */
data class DeliveryRecord(
    val eventSequenceId: Long,
    val sessionId: String?,
    val platform: Platform,
    val jobId: String,
    val taskId: String,
    val storeName: String?,
    val completedAt: Long,
    /** dropRealizedPay ?: totalPay ?: offerPayShare. */
    val realizedPay: Double?,
    /**
     * Provenance of [realizedPay] ([cloud.trotter.dashbuddy.domain.analytics.PayBasis]) — drives the
     * drill-down's "est. offer pay" qualifier on an `OFFER_PAY` estimate row (#691, the never-silent
     * disclosure the #689 precedent set).
     */
    val payBasis: String,
    /** Frozen realized net against the accepted offer's cost basis — never re-costed. */
    val netProfit: Double?,
    val realizedMiles: Double?,
    val realizedMinutes: Double?,
    val tip: Double?,
    val basePay: Double?,
    /**
     * Driver-entered cash tip (#688) — the driver-attested tip source. Added to gross/net at the
     * read sites only (never inside [realizedPay]/[netProfit]); null on a machine completion.
     */
    val cashTip: Double? = null,
    /**
     * Machine-computed to-store driving leg (#688 phase B) — fold provenance for the drill-down's
     * per-leg line. NOT rewritten by a driver miles edit, so it may disagree with [realizedMiles]
     * on a corrected row (the visible edit trail). Null on anchorless/pre-phase-B rows.
     */
    val milesToStore: Double? = null,
    /** Machine-computed to-dropoff driving leg (#688 phase B); same provenance rules as [milesToStore]. */
    val milesToDropoff: Double? = null,
)

/** One dash session. */
data class SessionRecord(
    val sessionId: String,
    val platform: Platform,
    val startedAt: Long,
    val endedAt: Long?,
    /** Platform summary-screen all-pay total (incl. bonuses/adjustments); null until a summary is seen. */
    val reportedEarnings: Double?,
    val reportedDurationMillis: Long?,
    /** Odometer delta (lastOdometer − startOdometer), floored at 0; null when odometer never seen. */
    val miles: Double?,
    val deliveries: Int,
    val jobsCompleted: Int,
    val offersReceived: Int,
    val offersAccepted: Int,
    val offersDeclined: Int,
    val offersTimeout: Int,
    /**
     * Σ driver-entered cash tips across this session's deliveries (#688 F7) — populated on the
     * recent-dashes read path only; `0.0` everywhere cash isn't queried. Rendered as an additive
     * "+cash" marker beside the reported earnings (never folded INTO [reportedEarnings], which stays
     * the platform-verbatim number).
     */
    val cashTips: Double = 0.0,
)
