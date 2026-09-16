package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.state.Platform

/**
 * The running fold state for one dash session (#314) — the analytics projector's per-session
 * accumulator, threaded through [RecordFolds.foldEvent] one event at a time.
 *
 * Pure value object, no Android / DB / wall clock: the projector ([cloud.trotter.dashbuddy.core.data]
 * `AnalyticsProjector`) hydrates it from the record tables on a mid-session restart and maps it to a
 * `SessionRecordEntity` at the transaction edge, but the fold arithmetic that advances it lives here
 * so it is `:domain:test`-able (same placement precedent as `DropPayApportioner`).
 *
 * Miles/minutes are **partition** anchors: [prevDropOdometer]/[prevDropAt] mark the previous
 * `DELIVERY_COMPLETED` in this session (or the DASH_START odometer/time for the first drop), so each
 * drop's realized miles/minutes are the gap since that anchor and Σ over the session equals the
 * session odometer/time delta with nothing double-counted.
 *
 * [lastEvaluatedCostPerMile] is the session-uniform operating cost-per-mile the offers were
 * evaluated against — the frozen-economy basis for every delivery in the session (see [RecordFolds]).
 */
data class SessionFoldContext(
    val sessionId: String,
    val platform: Platform,
    val startedAt: Long,
    /** Advanced on EVERY session event — the open-session duration + inferred-close anchor. */
    val lastEventAt: Long,
    val endedAt: Long? = null,
    val endSource: String? = null,
    /** metadata.odometer of DASH_START (first non-null wins; a RECOVERY re-start must not clobber it). */
    val startOdometer: Double? = null,
    /** Last non-null metadata.odometer seen — miles = lastOdometer − startOdometer, derived in SQL. */
    val lastOdometer: Double? = null,
    val reportedEarnings: Double? = null,
    val reportedDurationMillis: Long? = null,
    val offersAccepted: Int = 0,
    val offersDeclined: Int = 0,
    val offersTimeout: Int = 0,
    val deliveries: Int = 0,
    /** Distinct delivered jobIds — [jobsCompleted] is its size (counts a stacked job once). */
    val deliveredJobIds: Set<String> = emptySet(),
    /**
     * Distinct jobIds that have folded ≥1 drop with **receipt-derived** pay (`DROP_SHARE` /
     * `RECEIPT_TOTAL`) this session — the #691 mixed-receipt guard. A receipt-less drop of such a job
     * keeps `PayBasis.NONE` rather than stamping an offer-pay estimate (an equal split of the offer
     * total assumes the WHOLE job is receipt-less; mixing a real receipt with offer-share estimates
     * would over-count invisibly under the one-sided `MAX(reported − Σ, 0)` unattributed floor). This
     * is fold-side defense-in-depth — the EffectMap job-scoped stamp condition is the primary control.
     * NOT populated by `USER_CORRECTED` (a documented hydration wrinkle, #691 VET F1).
     */
    val receiptedJobIds: Set<String> = emptySet(),
    /** Partition anchor: odometer at the previous completion; null ⇒ fall back to [startOdometer]. */
    val prevDropOdometer: Double? = null,
    /** Partition anchor: time at the previous completion; null ⇒ fall back to [startedAt]. */
    val prevDropAt: Long? = null,
    /**
     * The operating cost-per-mile of the most recent closing offer with an evaluation, in this
     * session. Session-uniform (one `UserEconomy` per session), so it is the correct frozen basis
     * for any delivery in the session without a per-job offer↔delivery join (which the event log
     * cannot express — see [RecordFolds]).
     */
    val lastEvaluatedCostPerMile: Double? = null,
    /**
     * The fuel component of [lastEvaluatedCostPerMile] — the most recent closing offer's
     * `fuelCostEstimate ÷ distanceMiles` (per-mile), session-uniform like the cpm. Null until an
     * offer with a positive-distance evaluation is seen (a distance-0 offer captures cpm but no
     * split). The frozen fuel/non-fuel per-mile basis for the 4-step true-net waterfall (#659).
     */
    val lastEvaluatedFuelPerMile: Double? = null,
    /** The non-fuel component of [lastEvaluatedCostPerMile] — `nonFuelCostEstimate ÷ distanceMiles` (#659). */
    val lastEvaluatedNonFuelPerMile: Double? = null,
    /**
     * True once a real DASH_START has established this session's platform/startedAt anchor. A context
     * synthesized for a session-scoped event that arrived before its DASH_START (e.g. the
     * OFFER_RECEIVED ordered just ahead of DASH_START at the same instant) is a `_unknown` placeholder
     * with `started=false`; the DASH_START upgrades it (real platform/startedAt) instead of being
     * mistaken for a RECOVERY re-start that must not clobber.
     */
    val started: Boolean = false,
    /**
     * Per-leg mileage accumulator (#688 phase B) — the leg anchor + the pending to-store / to-dropoff
     * legs awaiting consumption at `DELIVERY_COMPLETED`. Persisted with the session row
     * (`legStateJson`, [LegStateCodec]) because pending legs describe not-yet-completed drops and so
     * cannot be re-derived from record rows on a batch-boundary rehydration. Default empty ⇒ a
     * synthesized/mid-session-start context (or all pre-odometer history) folds legs unknown and each
     * drop falls back to the legacy partition delta — byte-identical to today.
     */
    val legState: LegState = LegState(),
    /**
     * Provenance of this session's start: the DASH_START payload's `source` (e.g. "interaction",
     * "recovery") once a real start has been seen; null for a synthesized `_unknown` placeholder that
     * has not (#659, retro finding 2). The persisted marker the projector rehydrates `started` from —
     * so hydration no longer infers "saw DASH_START" from "has a real platform" (a row synthesized by
     * a real-platform DASH_STOP arriving before its DASH_START would otherwise rehydrate as started
     * and keep a near-zero-duration `startedAt`).
     */
    val startSource: String? = null,
) {
    val jobsCompleted: Int get() = deliveredJobIds.size
    val offersReceived: Int get() = offersAccepted + offersDeclined + offersTimeout
}
