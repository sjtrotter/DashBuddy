package cloud.trotter.dashbuddy.core.database.analytics

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable analytics read-model row — one per `DELIVERY_COMPLETED` event (#314).
 *
 * A record is an **immutable historical fact**, not a cache of live state. The
 * projector ([cloud.trotter.dashbuddy.core.data.analytics.AnalyticsProjector], PR2)
 * folds the `app_events` log into these rows; the primary key is the *source*
 * event's `sequenceId`, which is both its provenance and its idempotency key
 * (replay/refold with `@Insert(onConflict = REPLACE)` is a byte-identical no-op).
 *
 * Miles/minutes are stored as **partition deltas** — the odometer/time gap since
 * the previous `DELIVERY_COMPLETED` in the same session (or `DASH_START` for the
 * first drop) — so Σ per-drop deltas + the session tail equals the session
 * odometer delta with nothing double-counted. Task-scoped anchors
 * (`phaseStartedAt`/`arrivedAt`) are kept so task-scoped views stay derivable.
 *
 * **Frozen economics** ([frozenCostPerMile]/[netProfit]/[costBasis]) freeze the
 * operating-cost basis this delivery was costed at (inherited in PR2 from the
 * accepted offer's `OfferEvaluation.operatingCostPerMile`). Because a record is a
 * historical fact, a later economy edit MUST NOT retroactively change these —
 * they are a frozen decision, not a stale cache. [costBasis] records provenance
 * exactly as [payBasis] does for realized pay.
 *
 * `storeName` is a **merchant** name, not customer PII — fine at rest in the DB,
 * never emitted in INFO+ logs. `customerHash`/`addressHash` are already sha256'd
 * upstream at the edge; kept here for dedup/correlation and future chain/entity
 * resolution (#159), which becomes a clean additive later-add.
 */
@Entity(
    tableName = "delivery_records",
    indices = [
        Index("completedAt"),                       // null-session fallback window + per-day charts
        Index(value = ["platform", "completedAt"]), // per-platform ordering/charts (period bucketing is session-anchored, #655)
        Index("sessionId"),                         // per-dash drilldown + fold hydration
        Index(value = ["sessionId", "jobId"]),      // incremental distinct-job counting
        Index("storeName"),                         // per-store aggregation + chain resolution (#159)
    ]
)
data class DeliveryRecordEntity(
    /** sequenceId of the source DELIVERY_COMPLETED row in app_events — provenance AND idempotency. */
    @PrimaryKey val eventSequenceId: Long,
    /** app_events.aggregateId (nullable there → nullable here). */
    val sessionId: String?,
    /** Platform.wire ("doordash"), resolved from session context — NEVER id-parsed. */
    val platform: String,
    val jobId: String,
    val taskId: String,
    /** Merchant name — not customer PII; fine at rest, never in INFO logs. */
    val storeName: String?,
    /** Already sha256'd upstream (DeliveryPayload.customerHash). */
    val customerHash: String?,
    /** Already sha256'd upstream (DeliveryPayload.addressHash) — chain-resolution readiness (#159). */
    val addressHash: String?,
    val phaseStartedAt: Long,
    /** dwell = completedAt − arrivedAt is DERIVED, not stored. */
    val arrivedAt: Long?,
    /** payload.completedAt ?: event.occurredAt — the period bucket key. */
    val completedAt: Long,
    /** On-time margin for the Phase-4 Time tab — free to carry now. */
    val deadlineMillis: Long?,
    /** dropRealizedPay ?: totalPay. */
    val realizedPay: Double?,
    /** "DROP_SHARE" | "RECEIPT_TOTAL" | "NONE" — per-row honesty. */
    val payBasis: String,
    /** parsedPay.totalTip — ONLY when this is the job's sole drop, else null. */
    val tip: Double?,
    /** parsedPay.totalBasePay — same single-drop-only rule. */
    val basePay: Double?,
    /** Raw metadata.odometer reading — the next drop's delta anchor. */
    val odometerAtCompletion: Double?,
    /** Partition delta: odo(this) − odo(prev completion | DASH_START). */
    val realizedMiles: Double?,
    /** Partition delta: (completedAt − prev anchor time) / 60_000. */
    val realizedMinutes: Double?,

    // ── Frozen economics (immutable historical fact — never re-costed on economy edit) ──
    /** Operating cost-per-mile this delivery is costed at, frozen from the accepted offer (PR2). */
    val frozenCostPerMile: Double?,
    /** Frozen realized net = realizedPay − realizedMiles × frozenCostPerMile (PR2). */
    val netProfit: Double?,
    /** Provenance of the cost basis: "OFFER_FROZEN" | "CAPTURED" | "CURRENT_FALLBACK" | "NONE". */
    val costBasis: String,
)
