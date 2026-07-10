package cloud.trotter.dashbuddy.core.database.analytics

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable analytics read-model row — one per completed pickup (#159, the visits table, D1). Folded
 * from `PICKUP_CONFIRMED` (the closing event of the pickup phase, carrying the full
 * `phaseStartedAt/arrivedAt/confirmedAt` progression). A pickup that never confirms folds no row (no
 * dwell to report — a known v1 gap, F10).
 *
 * Dwell (`confirmedAt − arrivedAt`) is DERIVED in SQL, never stored (D1/SSOT). [storeKey] is null at
 * write time and stamped/re-stamped by store resolution (see `AnalyticsProjector`).
 *
 * `storeName` is MERCHANT data, not customer PII — fine at rest, never in INFO+ logs (P7).
 */
@Entity(
    tableName = "pickup_records",
    indices = [
        Index("sessionId"),   // per-dash / per-session enumeration
        Index("jobId"),       // resolution reads a job's committed pickups by jobId
        Index("storeKey"),    // per-store dwell aggregation
    ],
)
data class PickupRecordEntity(
    /** sequenceId of the source PICKUP_CONFIRMED row in app_events — provenance AND idempotency. */
    @PrimaryKey val eventSequenceId: Long,
    /** app_events.aggregateId (nullable there → nullable here). */
    val sessionId: String?,
    /** Platform.wire, resolved from session context — NEVER id-parsed. */
    val platform: String,
    val jobId: String,
    val taskId: String,
    /** Raw pickup-surface form (the canonical anchor). Merchant data, never in INFO logs. */
    val storeName: String,
    /** Resolved entity key — null at write, stamped/re-stamped by resolution. */
    val storeKey: String?,
    val phaseStartedAt: Long,
    /** dwell = confirmedAt − arrivedAt is DERIVED in SQL, never stored. */
    val arrivedAt: Long?,
    val confirmedAt: Long?,
    val deadlineMillis: Long?,
    /** Shop vs pickup — dwell populations differ; the report card can segment. */
    val activity: String?,
    /**
     * The parsed store address from the enriched `PickupPayload` (#159 D4), when present. Resolution
     * seeds `stores.address` from the first non-null value across a store's pickup rows — so the
     * enrichment must land on a queryable row (resolution reads rows, never a trigger event). Null on
     * all historical rows. MERCHANT data, never in INFO+ logs (P7). (Spec deviation: the spec's
     * pickup_records table omitted this column; it is the only row source for `stores.address`.)
     */
    val storeAddress: String? = null,
)
