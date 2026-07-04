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
    /** dropRealizedPay ?: totalPay. */
    val realizedPay: Double?,
    /** Frozen realized net against the accepted offer's cost basis — never re-costed. */
    val netProfit: Double?,
    val realizedMiles: Double?,
    val realizedMinutes: Double?,
    val tip: Double?,
    val basePay: Double?,
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
)
