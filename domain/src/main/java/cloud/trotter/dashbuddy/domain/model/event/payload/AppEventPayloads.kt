package cloud.trotter.dashbuddy.domain.model.event.payload

import kotlinx.serialization.Serializable

import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.state.Flow

/**
 * Rich payloads written into [cloud.trotter.dashbuddy.core.database.event.AppEventEntity.eventPayload]
 * at each phase-boundary. Each payload captures the full state of the phase
 * as it closed so the bubble HUD's flow-card stack can fold the event stream
 * into per-phase snapshots without joining other entities.
 *
 * `AppEventEntity.eventPayload` is a JSON `String`, so adding/removing fields
 * on these classes does not require a database migration. Old rows simply
 * deserialize into whatever fields they still have.
 */

/**
 * Marker for every typed app-event payload (#354). Lets the domain [
 * cloud.trotter.dashbuddy.domain.model.event.AppEvent] carry its payload as a
 * sealed type, and lets the codec dispatch exhaustively.
 */
sealed interface AppEventPayload

/**
 * Payload for `OFFER_RECEIVED` — emitted when a new offer first appears on
 * screen. Lean by design: the offer's evaluation hasn't run yet at this
 * point (the EvaluateOffer side effect fires async), so only the parsed
 * offer + identity fields are populated. The closing `OFFER_ACCEPTED` /
 * `OFFER_DECLINED` / `OFFER_TIMEOUT` event carries the rich evaluation.
 */
@Serializable
data class OfferReceivedPayload(
    val offerHash: String,
    val parsedOffer: ParsedOffer,
    val presentedAt: Long,
    val platform: String,
    val returnFlow: Flow,
) : AppEventPayload

/**
 * Payload for `OFFER_ACCEPTED` / `OFFER_DECLINED` / `OFFER_TIMEOUT`.
 *
 * Carries the full offer context and evaluation. The closing event alone is
 * sufficient to render the Offer card — no need to read OFFER_RECEIVED.
 */
@Serializable
data class OfferPayload(
    val offerHash: String,
    val parsedOffer: ParsedOffer,
    val evaluation: OfferEvaluation? = null,
    val outcome: AppEventType,
    val presentedAt: Long,
    val decidedAt: Long,
    val returnFlow: Flow,
    /** Optional context — e.g. "Replaced by new offer". */
    val description: String? = null,
) : AppEventPayload

/**
 * Payload for `PICKUP_NAV_STARTED`, `PICKUP_ARRIVED`, `PICKUP_CONFIRMED`.
 *
 * Each phase-boundary fills in progressively more fields:
 *   NAV_STARTED → phaseStartedAt + entry odometer + task metadata
 *   ARRIVED     → adds arrivedAt + arrival odometer
 *   CONFIRMED   → adds confirmedAt (the moment pickup→dropoff transition)
 */
@Serializable
data class PickupPayload(
    val jobId: String,
    val taskId: String,
    val storeName: String,
    val phaseStartedAt: Long,
    val arrivedAt: Long? = null,
    val confirmedAt: Long? = null,
    val odometerAtEntry: Double? = null,
    val odometerAtArrival: Double? = null,
    val deadlineMillis: Long? = null,
    val itemsRemaining: Int? = null,
    val itemsShopped: Int? = null,
    val redCardTotal: Double? = null,
    val activity: String? = null,
    /**
     * The parsed store address (#159 D4), when the pickup screen surfaced one. First-observed
     * non-null value seeds `stores.address` on the resolved entity — MERCHANT data, not customer
     * PII (fine at rest, never in INFO+ logs, P7). Null on all historical events (nullable-with-
     * default → old rows deserialize identically → no forced projector bump from this field).
     */
    val storeAddress: String? = null,
    /**
     * The job's contributing offer hashes (#159 D3, `Job.parentOfferHashes`) — the offer↔job link
     * the log otherwise lacks (`OFFER_ACCEPTED` fires before the job is minted). Enables the resolver
     * to stamp `offer_records.storeKey`/`linkedJobId` for the exact offer(s) of this job; degrades to
     * the session-scoped temporal fallback when empty (a site that can't reach the job, or a historical
     * event). NEVER used for money — the delivery `storeKey` comes from the pickup anchor, not the
     * offer. Nullable-with-default ⇒ old events deserialize identically (F12).
     */
    val jobOfferHashes: List<String> = emptyList(),
) : AppEventPayload

/**
 * Payload for `DELIVERY_NAV_STARTED`, `DELIVERY_ARRIVED`, `DELIVERY_COMPLETED`.
 *
 * `DELIVERY_COMPLETED` is the only one that carries the per-delivery pay
 * breakdown — captured from the most recent PostTask observation before
 * leaving the PostTask flow.
 */
@Serializable
data class DeliveryPayload(
    val jobId: String,
    val taskId: String,
    val storeName: String? = null,
    val customerHash: String? = null,
    val addressHash: String? = null,
    val phaseStartedAt: Long,
    val arrivedAt: Long? = null,
    val completedAt: Long? = null,
    val odometerAtEntry: Double? = null,
    val odometerAtArrival: Double? = null,
    val deadlineMillis: Long? = null,
    /** Total pay for this delivery — populated on COMPLETED. */
    val totalPay: Double? = null,
    /** Itemized pay breakdown — populated on COMPLETED. */
    val parsedPay: ParsedPay? = null,
    /**
     * This drop's attributed share of the job's combined receipt (#528 Slice A) — the exact
     * per-store tip matched to this drop plus an **equal-split** share of the lump base. The base
     * split is an honest v1 estimate (neither offer nor receipt breaks out per-order base); the
     * tip is exact when the drops map 1:1 onto the receipt's tip lines, else the whole receipt is
     * split evenly. Under the fielded one-receipt-per-job shape (DoorDash settles a stack with a
     * single end-of-job receipt), a stacked job's DELIVERY_COMPLETED rows sum EXACTLY to the receipt
     * total ([cloud.trotter.dashbuddy.domain.model.pay.ParsedPay.total]) in integer cents — within a
     * single [cloud.trotter.dashbuddy.domain.state.DropPayApportioner.apportion] call the last drop
     * absorbs the rounding remainder, and no drop double-counts the combined receipt. A platform that
     * exposes a *mid-stack* receipt (a partial or per-drop receipt that changes between completions)
     * can under-attribute — the sum degrades below the final total, never above it (tracked as a
     * #528 follow-up). Null when there is no itemized receipt to attribute (a receipt-skipped #596
     * completion, or a collapsed bare-total receipt). Computed by
     * [cloud.trotter.dashbuddy.domain.state.DropPayApportioner].
     */
    val dropRealizedPay: Double? = null,
    /**
     * This drop's equal-split share of the job's accepted-offer pay (#691) — an ESTIMATE, stamped
     * ONLY when the closing job was WHOLLY receipt-less (no receipt dollars anywhere on it) AND this
     * drop's own receipt inputs are absent, so a receipt-less shop delivery folds a real net row
     * instead of a $0-unattributed one. The platform's true per-drop split is unknowable without a
     * receipt, so the whole job's offer total is divided equally across its owed dropoffs
     * ([cloud.trotter.dashbuddy.domain.state.DropPayApportioner.equalSplit], cents-exact
     * remainder-to-last). Null on any receipted mint, on a pay-less offer, and on all pre-#691 events
     * (nullable-with-default → old rows deserialize identically → the fold reproduces today's
     * `PayBasis.NONE` rows byte-for-byte, no `PROJECTOR_VERSION` bump). Consumed by the fold only as
     * `PayBasis.OFFER_PAY` and only when no sibling drop of this job already folded a real receipt.
     */
    val offerPayShare: Double? = null,
    /** Session running total at the moment of completion. */
    val sessionEarningsAtCompletion: Double? = null,
    /**
     * The job's contributing offer hashes (#159 D3, `Job.parentOfferHashes`) — the offer↔job link the
     * log otherwise lacks (`OFFER_ACCEPTED` fires before the job is minted). The resolver stamps
     * `offer_records.storeKey`/`linkedJobId` for the exact offer(s) of this job; degrades to the
     * temporal fallback when empty (F12). NEVER used for money. Nullable-with-default ⇒ old events
     * deserialize identically.
     */
    val jobOfferHashes: List<String> = emptyList(),
) : AppEventPayload

/**
 * Payload for `MANUAL_DELIVERY` (#650) — a driver-entered missed delivery: a real drop the capture
 * pipeline never saw (an unrecognized screen, a crash, an offer taken outside the app). It is an
 * **event, never a destructive edit**: the projector folds it into a `delivery_record` (payBasis
 * `MANUAL`) exactly as it folds a captured completion, so the correction is durable, rebuild-faithful
 * (a from-zero refold reproduces it), and auditable — the `MANUAL` provenance keeps a driver's own
 * statement distinguishable from a machine capture forever.
 *
 * [pay] is the only required money field ([tip]/[miles] are optional driver knowledge); [miles] is
 * the driver's own statement of the drop's distance, NOT a partition delta off the odometer — a
 * manual delivery must not perturb the surrounding machine rows' mileage anchors on a refold.
 * [note] is driver-authored free text (driver-owned local data; never emitted in INFO+ logs, P7).
 */
@Serializable
data class ManualDeliveryPayload(
    /** The dash session this correction belongs to. */
    val sessionId: String,
    /** Driver-entered merchant name, optional. */
    val storeName: String? = null,
    /** Required — the driver's stated pay for this delivery. */
    val pay: Double,
    /** Platform-reported tip already included in [pay] (itemization only; sole-drop). */
    val tip: Double? = null,
    /**
     * Driver-entered CASH tip (#688) — the tip vocabulary's driver-attested source. Kept OUTSIDE
     * [pay] (cash is added to gross/net only at the read sites, never baked into realizedPay/net) so
     * the reconciliation's Σ-attributed stays structurally cash-free. Nullable-with-default: old
     * events deserialize identically.
     */
    val cashTip: Double? = null,
    /** When the delivery happened; v1 the caller defaults it (e.g. the session's end/start). */
    val completedAt: Long,
    /** Driver-known miles, optional — the driver's statement, not an odometer partition delta. */
    val miles: Double? = null,
    /** Driver-authored free text — driver-owned local data, never in INFO+ logs (P7). */
    val note: String? = null,
) : AppEventPayload

/**
 * Payload for `DELIVERY_ADJUSTMENT` (#688) — a driver multi-field edit of an already-recorded
 * delivery, WIDENING [PayAdjustmentPayload] (which stays fully supported for history). It is an
 * **event, never a destructive edit**: the original `DELIVERY_COMPLETED` (or `MANUAL_DELIVERY`)
 * event and its provenance stay in the log; the projector re-applies the target `delivery_record`
 * in place inside the batch transaction. Because a correction always sequences AFTER its target, a
 * from-zero refold replays them in order and reproduces identical rows.
 *
 * Every new-value field is ALL-OPTIONAL: only the fields the driver changed are non-null, and a
 * null field leaves the target row's value untouched. The provenance rules the orchestrator applies:
 * a machine row's `payBasis` flips to `USER_CORRECTED` **iff [newPay] is non-null** (#688 VET F1 —
 * payBasis rewrites exactly when realizedPay does, so a store-name/tip/cash edit never drops an
 * "est. offer pay" disclosure); a MANUAL row stays MANUAL; cash/tip are driver-attested side columns
 * (a [newCashTip]/[newTip]-only edit touches neither basis nor net).
 *
 * There is deliberately **no `newCompletedAt`** (#688 VET F2): editing the latest row's completedAt
 * would make restart-incremental folding disagree with a from-zero refold (restart hydration reads
 * `prevDropAt` from the corrected row while live context uses the payload's), reintroducing the
 * determinism class #703 closes. Re-adding it later requires correction-immune anchor hydration
 * (the projector must resolve `prevDropAt` from an origin the correction can't move).
 */
@Serializable
data class DeliveryAdjustmentPayload(
    /** PK (source `sequenceId`) of the `delivery_record` being edited. */
    val targetEventSequenceId: Long,
    /** For session attribution / liveness only — the fold never reads the target row through it. */
    val sessionId: String? = null,
    /** Driver-entered merchant name; null ⇒ unchanged. */
    val newStoreName: String? = null,
    /** New realized pay (> 0); null ⇒ unchanged. Flips a machine row to `USER_CORRECTED` (VET F1). */
    val newPay: Double? = null,
    /** New platform-reported tip already inside pay (itemization only, ≥ 0); null ⇒ unchanged. */
    val newTip: Double? = null,
    /** New driver-entered cash tip (≥ 0); null ⇒ unchanged. Side column — never touches basis/net. */
    val newCashTip: Double? = null,
    /** New driver-stated miles (≥ 0); null ⇒ unchanged. Recomputes net (no basis flip on its own). */
    val newMiles: Double? = null,
    /** Driver-authored free text — driver-owned local data, never in INFO+ logs (P7). */
    val note: String? = null,
) : AppEventPayload

/**
 * Payload for `PAY_ADJUSTMENT` (#650) — a driver re-price of an already-recorded delivery (a
 * mis-captured tip, a late-arriving adjustment). It is an **event, never a destructive edit**: the
 * original `DELIVERY_COMPLETED` event and its provenance stay in the log untouched; the projector
 * re-prices the target `delivery_record` in place (payBasis flips to `USER_CORRECTED`, net recomputed
 * against the row's OWN frozen cost basis — never re-costed by today's economy). Because a correction
 * always sequences AFTER its target's completion, a from-zero refold replays them in order and
 * reproduces identical rows; the `USER_CORRECTED` provenance keeps machine-vs-user distinguishable.
 */
@Serializable
data class PayAdjustmentPayload(
    /** PK (source `sequenceId`) of the `delivery_record` being re-priced. */
    val targetEventSequenceId: Long,
    /** For session attribution / liveness only — the fold never reads the target row through it. */
    val sessionId: String? = null,
    val newPay: Double,
    /** Driver-authored free text — driver-owned local data, never in INFO+ logs (P7). */
    val note: String? = null,
) : AppEventPayload

/** Wire values for [SessionStartPayload.source] (#366) — mirrors [SessionEndSource]. */
object SessionStartSource {
    /** Normal user-initiated start. */
    const val INTERACTION = "interaction"

    /** Session resurrected by crash recovery. */
    const val RECOVERY = "recovery"
}

/** Payload for `DASH_START`. */
@Serializable
data class SessionStartPayload(
    val sessionId: String,
    val platform: String,
    val startedAt: Long,
    /** One of [SessionStartSource]. */
    val source: String,
    val startScreen: String,
) : AppEventPayload

/**
 * Payload for `DASH_PAUSED`. The platform's reported pause countdown is
 * captured so the card stack can render a frozen "paused for Xm Ys" view.
 */
@Serializable
data class SessionPausedPayload(
    val sessionId: String?,
    val pausedAt: Long,
    val remainingText: String? = null,
    val remainingMillis: Long? = null,
    /**
     * The platform's enum name (e.g. "DoorDash"), stamped at the mint site (#314). Hardens the
     * log so a session-scoped fold can resolve platform without a prior DASH_START in the same
     * batch — the projector still prefers the session context, this is a self-contained fallback.
     * JSON-only field: adding it needs no Room migration (`AppEventEntity.eventPayload` is text).
     */
    val platform: String? = null,
) : AppEventPayload

/** Wire values for [SessionStopPayload.source]. Kept as strings on the payload
 *  (domain is gson-free); these are the single source of truth on the Kotlin side. */
object SessionEndSource {
    /** The dash-summary screen was observed — rich totals populated. */
    const val SUMMARY_SCREEN = "summary_screen"
    /** Went offline without a summary (grace expiry / early offline) — thin. */
    const val EARLY_OFFLINE = "early_offline"
}

/**
 * Payload for `DASH_STOP`.
 *
 * When the dash ends on a SessionEnded summary screen, the full earnings
 * picture is populated. When it ends via an early offline transition without
 * a summary screen, only the `source` field is set — the card UI shows an
 * "incomplete" badge for that case.
 */
@Serializable
data class SessionStopPayload(
    val sessionId: String?,
    val endedAt: Long,
    /** One of [SessionEndSource]. */
    val source: String,
    val totalEarnings: Double? = null,
    val sessionDurationMillis: Long? = null,
    val offersAccepted: Int? = null,
    val offersTotal: Int? = null,
    val weeklyEarnings: Double? = null,
    /**
     * The platform's enum name (e.g. "DoorDash"), stamped at the mint site (#314). Recovers the
     * session-miles capture gap's platform half for a session whose DASH_START predates the fold
     * watermark — a self-contained fallback; the projector still prefers the session context.
     * JSON-only field: adding it needs no Room migration (`AppEventEntity.eventPayload` is text).
     */
    val platform: String? = null,
) : AppEventPayload
