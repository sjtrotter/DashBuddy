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
    /**
     * Fuel component of [frozenCostPerMile] (per-mile), frozen from the accepted offer's own
     * evaluation (#659). Populated only off an `OFFER_FROZEN` basis; null for CURRENT_FALLBACK/NONE
     * (the live economy read supplies a bare cpm, not its split) → the 4-step true-net waterfall
     * falls back to 3-step for such rows. `frozenFuelPerMile + frozenNonFuelPerMile ≈
     * frozenCostPerMile` when all present.
     */
    val frozenFuelPerMile: Double? = null,
    /** Non-fuel component of [frozenCostPerMile] (per-mile), same OFFER_FROZEN-only rule (#659). */
    val frozenNonFuelPerMile: Double? = null,
    /** Frozen realized net = realizedPay − realizedMiles × frozenCostPerMile (PR2). */
    val netProfit: Double?,
    /** Provenance of the cost basis: "OFFER_FROZEN" | "CAPTURED" | "CURRENT_FALLBACK" | "NONE". */
    val costBasis: String,

    // ── Driver-attested side columns (#688, v11) ────────────────────────────
    /**
     * Driver-entered CASH tip (#688). The tip provenance vocabulary: [tip] = platform-reported
     * (on-app / receipt `totalTip`, sole-drop) · `cashTip` = driver-entered cash · a #550
     * post-delivery additional tip is a future third source (its own column when the toast capture
     * lands). Written ONLY by driver events (DELIVERY_ADJUSTMENT / MANUAL_DELIVERY), never by a
     * machine completion. **Locked accounting:** cash lives OUTSIDE [realizedPay] and [netProfit] —
     * it is added to gross/net only at the read sites, so the reconciliation's Σ-attributed
     * (`SUM(realizedPay)`) stays structurally cash-free.
     */
    val cashTip: Double? = null,
    /**
     * The [payBasis] stamped at FIRST fold (#703), **never rewritten by any correction** (every
     * orchestrator apply preserves it via `row.copy`; only the fold-time `toEntity` sets it). The
     * #691 receipt-evidence hydration reads `COALESCE(originalPayBasis, payBasis)`, so a row re-priced
     * to `USER_CORRECTED` still proves its original receipt evidence to a receipt-less sibling. Null
     * on legacy rows until the `PROJECTOR_VERSION` 3→4 refold populates it from the immutable log.
     */
    val originalPayBasis: String? = null,
)
