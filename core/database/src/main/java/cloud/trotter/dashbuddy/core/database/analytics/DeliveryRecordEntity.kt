package cloud.trotter.dashbuddy.core.database.analytics

import androidx.room.ColumnInfo
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
        Index("jobId"),                             // store resolution reads a job's rows by jobId (#159)
        Index("storeName"),                         // per-store aggregation + chain resolution (#159)
        Index("storeKey"),                          // resolved per-store economics grouping (#159)
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

    // ── Store entity resolution (#159, v12) ─────────────────────────────────
    /**
     * The resolved store entity key (`StoreKeys.storeKey`, #159), stamped/re-stamped by store
     * resolution against the pickup anchor; null until resolved (a MANUAL row never in a job, or a
     * not-yet-resolved row). The per-store economics query groups on it, falling back to the read-side
     * `normalizedChain(storeName)` for unresolved rows (F9). Refold-deterministic (row-sourced key).
     */
    val storeKey: String? = null,
    /**
     * The FULL receipt store-form set (#159 B1/B2) — every `parsedPay.customerTips[].type` on the ONE
     * completion that carried `parsedPay`, serialized as a JSON string array (null on the sibling
     * drops that carried no receipt). Persists ALL the receipt's store lines (not just this drop's),
     * because a stacked job settles on one end-of-job receipt riding a single completion — so resolution
     * reads the running keys from ROWS (never a trigger event), keeping every store keyed and the key
     * monotonic across a payout-less re-run. Merchant strings — fine at rest, never an INFO+ log
     * surface (P7).
     *
     * **Named residual (FIX 13):** these strings come from the receipt-partition heuristic
     * (`parsedPay.customerTips[].type`) and are merchant-shaped ONLY under the fielded DoorDash receipt.
     * A future/unfielded platform that itemizes tips *per customer* would land customer-shaped text here,
     * so any consumer that ever surfaces this column (a CSV/UI export) MUST scrub it — it is not
     * guaranteed to be merchant-only for every platform. No consumer exports it today.
     */
    val payoutStoreForms: String? = null,
    /**
     * Driver-correction sticky bit (#159 H1). Set to 1 when a `DELIVERY_ADJUSTMENT` supplies a
     * `newStoreName`; resolution SKIPS re-stamping any pinned row (`WHERE storeKeyPinned = 0`), so a
     * correction that disagrees with the pickup anchor is never re-keyed back. Derived from the
     * `DELIVERY_ADJUSTMENT` event ⇒ a from-zero refold re-derives it. Default 0 (SQL column default so
     * the additive AutoMigration back-fills existing rows).
     */
    @ColumnInfo(defaultValue = "0") val storeKeyPinned: Int = 0,

    // ── Per-leg mileage (#688 phase B, v13) ─────────────────────────────────
    /**
     * Machine-computed to-store driving leg (#688 phase B) — this drop's claimed store leg (exact
     * NORMALIZED-chain store-form match within the job, else FIFO). Stamped ONLY on a leg-sum row
     * ([milesToDropoff] non-null); a LEGACY row (missed arrival) stamps null and instead retires its
     * job's already-closed store legs (#688 review Fix 1/Fix 4), so a lone store leg never rides an
     * otherwise-untouched legacy row — keeping `milesToStore + milesToDropoff != realizedMiles`
     * meaningful strictly as the driver-edit trail. Provenance ONLY: a driver `newMiles`
     * DELIVERY_ADJUSTMENT rewrites [realizedMiles] but NEVER this column, so `milesToStore +
     * milesToDropoff` may then disagree with `realizedMiles` — that inequality is the visible edit
     * trail (DEV-DECISION 1). Null on an anchorless/unclaimed/legacy leg and all pre-v13 history.
     */
    val milesToStore: Double? = null,
    /**
     * Machine-computed to-dropoff driving leg (#688 phase B), keyed by this drop's own `taskId`. When
     * non-null, `realizedMiles == (milesToStore ?: 0) + milesToDropoff` (the leg-sum rule); when null
     * the row keeps the legacy partition delta. Null when no odometer-bearing `DELIVERY_ARRIVED`
     * preceded the completion (missed arrival, phantom-class completion, all pre-v13 history).
     */
    val milesToDropoff: Double? = null,
)
