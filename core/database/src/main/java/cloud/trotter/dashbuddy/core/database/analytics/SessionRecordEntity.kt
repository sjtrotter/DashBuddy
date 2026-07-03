package cloud.trotter.dashbuddy.core.database.analytics

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable analytics read-model row — one per dash session, upserted incrementally
 * as the projector folds each session-attributed event (#314).
 *
 * The natural key is the `sessionId`, so upserts are idempotent. Counters
 * ([offersReceived]…[jobsCompleted]) are folded (not derived-only) because the
 * transactional watermark makes each source event exactly-once, and a
 * self-contained row is the "recent dashes" list item the future hub renders.
 *
 * Session **miles** and **delivered pay** are deliberately NOT columns (SSOT,
 * Principle 5): miles = `lastOdometer − startOdometer` (a SQL expression), pay =
 * SUM over `delivery_records`. Storing them would be a second owner for the same
 * number. [reportedEarnings]/[reportedDurationMillis] are the platform's own
 * summary-screen totals (incl. bonuses/adjustments) — kept so the "unattributed"
 * delta (reported − Σdeliveries) is a repository-level review flag later (PR3),
 * needing no extra column.
 *
 * Closes the named session-miles capture gap: `SessionStopPayload` omits miles,
 * so they are recovered from the odometer delta across the session's event rows.
 */
@Entity(
    tableName = "session_records",
    indices = [
        Index("startedAt"),                     // period predicates for sessions
        Index(value = ["platform", "startedAt"]), // per-platform periods
    ]
)
data class SessionRecordEntity(
    /** Natural key; upsert-idempotent. */
    @PrimaryKey val sessionId: String,
    /** Platform.wire, from SessionStartPayload.platform via Platform.fromName. */
    val platform: String,
    /** Period bucket key for sessions. */
    val startedAt: Long,
    /** Null while live / crash-orphaned. */
    val endedAt: Long?,
    /** Advanced on EVERY session event — open-session duration + inferred close. */
    val lastEventAt: Long,
    /** "summary_screen" | "early_offline" | "inferred" | null. */
    val endSource: String?,
    /** metadata.odometer of DASH_START (first non-null wins). */
    val startOdometer: Double?,
    /** Last non-null metadata.odometer seen — miles = lastOdometer − startOdometer, DERIVED in SQL. */
    val lastOdometer: Double?,
    /** SessionStopPayload.totalEarnings (summary screen only) — all-pay, incl. bonuses. */
    val reportedEarnings: Double?,
    /** SessionStopPayload.sessionDurationMillis. */
    val reportedDurationMillis: Long?,
    // Folded outcome counts:
    val offersReceived: Int,
    val offersAccepted: Int,
    val offersDeclined: Int,
    val offersTimeout: Int,
    /** Folded count of DELIVERY_COMPLETED. */
    val deliveries: Int,
    /** Folded count of distinct jobIds delivered. */
    val jobsCompleted: Int,
)
