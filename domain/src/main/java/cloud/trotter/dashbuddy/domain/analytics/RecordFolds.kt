package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.evaluation.NetProfit
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.SequencedAppEvent
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryAdjustmentPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.ManualDeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PayAdjustmentPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.state.Platform

/** Provenance of a delivery's realized pay (#314) — mirrors the DB column, owned here as SSOT. */
object PayBasis {
    /** The drop's apportioned share of a combined receipt (`DropPayApportioner`, #528). */
    const val DROP_SHARE = "DROP_SHARE"
    /** The whole receipt total, stamped on this drop (single-drop / collapsed-receipt shape). */
    const val RECEIPT_TOTAL = "RECEIPT_TOTAL"
    /**
     * A full-receipt stamp on an IDENTITY-LESS drop of an ALREADY-delivered (multi-delivery)
     * job — dropped as suspect (#653). An identity-less phantom drop (customer AND address hash
     * both null — the #498 class, by construction: the payload copies the task's null hashes) in
     * a receipted stack arrives with the whole `parsedPay` and NO apportioned `dropRealizedPay`
     * share, while its sibling drops' shares already sum to that receipt; stamping the full
     * receipt here would double-count the period SUM. Fold-side defense-in-depth behind the
     * #498/#653 upstream identity firewall — no realizedPay/tip/base is recorded for such a row.
     * The identity check is load-bearing: an identity-BEARING full-receipt row on a multi-drop
     * job is the LEGITIMATE pre-#528 historical mint shape (N−1 null-pay rows + one announced
     * drop carrying the whole receipt — the job's only money) and must keep [RECEIPT_TOTAL].
     * A SUSPECT row is kept for auditability (this provenance names why its money is null) and
     * still counts as a delivery / advances the partition anchors — only its money is nulled.
     */
    const val SUSPECT_FULL_RECEIPT = "SUSPECT_FULL_RECEIPT"
    /**
     * An ESTIMATE basis (#691): the accepted offer's guaranteed total, split EQUALLY across the
     * job's owed dropoffs, stamped at completion when the WHOLE job was receipt-less (a DoorDash
     * shop order shows no per-delivery receipt — pay lives only on the offer + the running dash
     * total). The platform's actual per-drop split is unknowable without a receipt, so the honest
     * estimate is the offer total ÷ drops. Consumed only when no sibling drop of the same job folded
     * with receipt-derived pay (the mixed-receipt guard); tip/base stay null (no itemization).
     * Surfaced as "est. offer pay" in the UI and as `OFFER_PAY` in the CSV `pay_basis` column so the
     * estimate is never silent (the #689 precedent).
     */
    const val OFFER_PAY = "OFFER_PAY"

    /** No pay signal on the completion (receipt-skipped #596). */
    const val NONE = "NONE"

    /**
     * A driver-entered missed delivery (#650); pay/tip/miles are the driver's own statement, not a
     * capture. Keeps a manual correction distinguishable from a machine-captured row forever.
     */
    const val MANUAL = "MANUAL"

    /**
     * A machine-captured row whose pay was re-priced by a driver PAY_ADJUSTMENT (#650); the original
     * event remains in the log — the re-price is a later event, never a destructive edit.
     */
    const val USER_CORRECTED = "USER_CORRECTED"

    /**
     * The bases that PROVE a receipt existed for a job (#691 mixed-receipt guard). [DROP_SHARE] /
     * [RECEIPT_TOTAL] carry receipt dollars directly; [SUSPECT_FULL_RECEIPT] is a receipt whose
     * money was nulled as a double-count guard (#653) — the receipt still HAPPENED, so it must deny
     * the offer-pay estimate to a receipt-less sibling of the same job. This is the ONE definition
     * both sides of the guard derive from: the in-fold `receiptedJobIds` accumulation here and the
     * `AnalyticsDao.receiptedJobIdsInSession` hydration IN-list (built from the same const vals).
     * Deliberately EXCLUDES [USER_CORRECTED] (formerly a hydration wrinkle, #691 VET F1; CLOSED by
     * the v11 receipt-evidence persistence, #703 — the DAO now hydrates on `COALESCE(originalPayBasis,
     * payBasis)`, so a re-priced row keeps its FIRST-fold basis as receipt evidence) and the
     * estimate/none bases.
     */
    val RECEIPT_EVIDENCE: List<String> = listOf(DROP_SHARE, RECEIPT_TOTAL, SUSPECT_FULL_RECEIPT)
}

/**
 * Provenance of a delivery's frozen operating-cost-per-mile (#314) — an IMMUTABLE fact, never
 * re-costed when the economy editor changes. Owned here as the SSOT the fold and the DB share.
 */
object CostBasis {
    /**
     * From a closing offer's frozen `OfferEvaluation.operatingCostPerMile` in the same session.
     * Correlated at **session** granularity (not a per-job offer↔delivery join, which the event log
     * cannot express): operating-cost-per-mile derives from the one `UserEconomy` a session runs
     * under, so every offer in the session shares it and it is the correct basis for any delivery.
     */
    const val OFFER_FROZEN = "OFFER_FROZEN"

    /** Stamped self-contained into the completion event (reserved; no live source stamps it yet). */
    const val CAPTURED = "CAPTURED"

    /**
     * No offer basis available (a delivery in an offer-less/healed session, or one whose offer
     * predates the fold watermark) — the current economy's cpm was used. Honestly flagged; NOT
     * rebuild-stable (a later refold re-stamps it with that day's economy).
     */
    const val CURRENT_FALLBACK = "CURRENT_FALLBACK"

    /** Neither an offer basis nor a current economy — no net computable. */
    const val NONE = "NONE"
}

/**
 * A delivery read-model row as produced by the pure fold — the `:domain` shape the projector maps
 * 1:1 onto `DeliveryRecordEntity` at the transaction edge (`:domain` cannot see `:core:database`).
 */
data class DeliveryFold(
    val eventSequenceId: Long,
    val sessionId: String?,
    val platform: String,
    val jobId: String,
    val taskId: String,
    val storeName: String?,
    val customerHash: String?,
    val addressHash: String?,
    val phaseStartedAt: Long,
    val arrivedAt: Long?,
    val completedAt: Long,
    val deadlineMillis: Long?,
    val realizedPay: Double?,
    val payBasis: String,
    val tip: Double?,
    /** Driver-entered cash tip (#688) — set only on a MANUAL fold; null on a machine completion. */
    val cashTip: Double?,
    val basePay: Double?,
    val odometerAtCompletion: Double?,
    val realizedMiles: Double?,
    val realizedMinutes: Double?,
    val frozenCostPerMile: Double?,
    /** Fuel component of [frozenCostPerMile] (per-mile), frozen from the offer basis; null off it (#659). */
    val frozenFuelPerMile: Double?,
    /** Non-fuel component of [frozenCostPerMile] (per-mile), frozen from the offer basis; null off it (#659). */
    val frozenNonFuelPerMile: Double?,
    val netProfit: Double?,
    val costBasis: String,
    /**
     * The FULL receipt store-form set (#159 B1/B2) — every `parsedPay.customerTips[].type` on the ONE
     * completion that carried `parsedPay`; null on receipt-less drops. Persisted to
     * `delivery_records.payoutStoreForms` (serialized), so store resolution reads the running keys from
     * ROWS (never a trigger event), keeping every store of a multi-store stack keyed and monotonic.
     */
    val payoutStoreForms: List<String>? = null,
    /** The job's contributing offer hashes (#159 D3) — the offer↔job link for resolution; never money. */
    val jobOfferHashes: List<String> = emptyList(),
)

/**
 * A completed-pickup read-model row as produced by the pure fold (#159) — mapped 1:1 onto
 * `PickupRecordEntity`. Folded from `PICKUP_CONFIRMED` (the closing event of the pickup phase);
 * `storeKey` is stamped later by store resolution, so it is not on this fold shape.
 */
data class PickupFold(
    val eventSequenceId: Long,
    val sessionId: String?,
    val platform: String,
    val jobId: String,
    val taskId: String,
    val storeName: String,
    val phaseStartedAt: Long,
    val arrivedAt: Long?,
    val confirmedAt: Long?,
    val deadlineMillis: Long?,
    val activity: String?,
    /** Enriched store address (#159 D4) — the only row source for `stores.address`. */
    val storeAddress: String?,
    /** The job's contributing offer hashes (#159 D3) — carried for the offer↔job link at resolution. */
    val jobOfferHashes: List<String> = emptyList(),
)

/**
 * A store-resolution trigger (#159) the pure fold emits for the orchestrator to run INSIDE the batch
 * transaction against the job's committed rows (resolve-from-rows — there is NO per-job accumulator,
 * F1). Emitted on every `DELIVERY_COMPLETED` (job-scoped) and `DASH_STOP` (session-scoped: [jobId]
 * null ⇒ enumerate the session's jobs, L2); the projector also emits one for each inferred-closed
 * session. Re-runnable and convergent: because resolution reads committed rows (never a single-event
 * payout), a later trigger recomputes the SAME keys (B1).
 */
data class StoreResolution(
    val sessionId: String?,
    val platform: String,
    /** null ⇒ session-level: enumerate the session's jobs and resolve each. */
    val jobId: String?,
    /** The job's offer hashes for the exact offer↔job link; empty ⇒ temporal fallback (F4/F12). */
    val offerHashes: List<String>,
    /** The triggering event's timestamp — advances `stores.lastSeenAt` (never a wall clock). */
    val at: Long,
)

/** An offer read-model row as produced by the pure fold — mapped 1:1 onto `OfferRecordEntity`. */
data class OfferFold(
    val eventSequenceId: Long,
    val sessionId: String?,
    val platform: String,
    val offerHash: String,
    val outcome: String,
    val presentedAt: Long,
    val decidedAt: Long,
    val payAmount: Double?,
    val distanceMiles: Double?,
    val itemCount: Int,
    val merchantName: String?,
    val score: Double?,
    val action: String?,
    val quality: String?,
    val estNetPay: Double?,
    val estDollarsPerHour: Double?,
    val estDollarsPerMile: Double?,
    val estTimeMinutes: Double?,
    val estOperatingCostPerMile: Double?,
    /** `fuelCostEstimate ÷ distanceMiles` (per-mile); null when distance ≤ 0. Rebuild-stable split hydration (#659). */
    val estFuelPerMile: Double?,
    /** `nonFuelCostEstimate ÷ distanceMiles` (per-mile); null when distance ≤ 0 (#659). */
    val estNonFuelPerMile: Double?,
)

/**
 * The result of folding ONE event: the updated session [context] (null when the event carries no
 * sessionId and so touches no session), any [delivery]/[offer] record it produced, and — for a
 * DASH_START whose session was not previously known — [freshSession] so the orchestrator can run
 * the inferred-close of prior open sessions. [skip] names why an event produced nothing (a null/
 * malformed payload), so the projector can count + WARN per batch.
 */
data class FoldOutcome(
    val context: SessionFoldContext? = null,
    val delivery: DeliveryFold? = null,
    val offer: OfferFold? = null,
    val freshSession: Boolean = false,
    val skip: String? = null,
    /**
     * A driver PAY_ADJUSTMENT decision (#650): the pure fold decides WHICH row to re-price and to
     * what, but cannot read the target `delivery_record` (it is not the session accumulator) — the
     * orchestrator applies it inside the batch transaction after the delivery/offer upserts.
     */
    val payAdjustment: PayAdjustmentFold? = null,
    /**
     * A driver DELIVERY_ADJUSTMENT decision (#688): the widened multi-field re-apply. Same shape as
     * [payAdjustment] — the pure fold decides WHICH row and WHICH fields change; the orchestrator
     * applies it by-PK inside the batch transaction after the upserts.
     */
    val deliveryAdjustment: DeliveryAdjustmentFold? = null,
    /** A completed-pickup visit row (#159), from `PICKUP_CONFIRMED`. */
    val pickup: PickupFold? = null,
    /** A store-resolution trigger (#159) the orchestrator runs against the job's committed rows. */
    val resolution: StoreResolution? = null,
)

/**
 * A driver's re-price decision (#650) — the pure fold's output for a PAY_ADJUSTMENT. The orchestrator
 * looks up [targetEventSequenceId] in `delivery_records` and rewrites its realizedPay to [newPay]
 * (payBasis → `USER_CORRECTED`, net recomputed against the row's OWN frozen cost basis). Because a
 * PAY_ADJUSTMENT always sequences after its target's DELIVERY_COMPLETED, a from-zero refold replays
 * them in order and reproduces identical rows.
 */
data class PayAdjustmentFold(
    val targetEventSequenceId: Long,
    val newPay: Double,
)

/**
 * A driver's widened re-apply decision (#688) — the pure fold's output for a DELIVERY_ADJUSTMENT.
 * Every field is optional (null ⇒ that column of the target row is left unchanged); the orchestrator
 * looks up [targetEventSequenceId] and copies each non-null field into the row, applying the basis /
 * net rules (payBasis flips to `USER_CORRECTED` iff [newPay] is non-null on a machine row; MANUAL
 * stays MANUAL; a cash/tip/store-only edit touches neither basis nor net). Because a DELIVERY_ADJUSTMENT
 * always sequences after its target, a from-zero refold replays them in order and reproduces identical
 * rows. `note` is intentionally absent — it lives only in the event payload and changes no column.
 */
data class DeliveryAdjustmentFold(
    val targetEventSequenceId: Long,
    val newStoreName: String? = null,
    val newPay: Double? = null,
    val newTip: Double? = null,
    val newCashTip: Double? = null,
    val newMiles: Double? = null,
)

/**
 * The pure event-sourced fold (#314): `(event, session context, current cpm) → record deltas`.
 *
 * No Android, no DB, no wall clock — driven only by the event's own timestamps and metadata, so a
 * backfill from watermark 0 and an incremental catch-up fold produce the same rows, and a
 * `projectorVersion` rebuild reproduces them. The orchestrator owns everything impure: paging,
 * context hydration from the record tables, the inferred-close DB query, and the records+watermark
 * transaction — and that transaction is what makes the fold **exactly-once**: delivery/offer rows
 * are keyed by source `sequenceId` (REPLACE-idempotent), but the folded session *counters* are not,
 * so re-processing an already-folded event WOULD double them — which the atomic watermark prevents
 * (a re-run over a drained log folds nothing; only new events past the watermark are ever folded).
 *
 * [currentCostPerMile] is the live economy's operating cost-per-mile, supplied by the orchestrator
 * only for the [CostBasis.CURRENT_FALLBACK] path (a delivery with no offer basis); it never
 * overrides an [CostBasis.OFFER_FROZEN] basis, so an economy edit cannot move a frozen row that has
 * one.
 */
object RecordFolds {

    fun foldEvent(
        event: SequencedAppEvent,
        context: SessionFoldContext?,
        currentCostPerMile: Double?,
    ): FoldOutcome {
        val e = event.event
        val sid = e.sessionId
        val odo = event.metadata?.odometer
        return when (e.type) {
            AppEventType.DASH_START -> foldDashStart(event, context)
            AppEventType.OFFER_ACCEPTED,
            AppEventType.OFFER_DECLINED,
            AppEventType.OFFER_TIMEOUT -> foldOffer(event, context)
            AppEventType.DELIVERY_COMPLETED -> foldDeliveryCompleted(event, context, currentCostPerMile)
            AppEventType.PICKUP_CONFIRMED -> foldPickupConfirmed(event, context)
            AppEventType.MANUAL_DELIVERY -> foldManualDelivery(event, context, currentCostPerMile)
            AppEventType.PAY_ADJUSTMENT -> foldPayAdjustment(event, context)
            AppEventType.DELIVERY_ADJUSTMENT -> foldDeliveryAdjustment(event, context)
            AppEventType.DASH_STOP -> foldDashStop(event, context)
            // Every other session-attributed event just advances the liveness/odometer anchors so
            // the open-session duration and lastOdometer stay honest; the watermark still moves past
            // them. Events with no session touch nothing.
            else -> {
                if (sid == null) return FoldOutcome(context = context)
                val ctx = resolveContext(context, sid, e.occurredAt)
                FoldOutcome(context = ctx.advance(e.occurredAt, odo))
            }
        }
    }

    private fun foldDashStart(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? SessionStartPayload
            ?: return FoldOutcome(context = context, skip = "DASH_START: missing/malformed payload")
        val sid = e.sessionId ?: p.sessionId
            ?: return FoldOutcome(context = context, skip = "DASH_START: no sessionId")
        val platform = Platform.fromName(p.platform) ?: Platform.Unknown
        val odo = event.metadata?.odometer
        // A RECOVERY (or any duplicate) re-start of a session that was ALREADY STARTED WITH A REAL
        // PLATFORM must NOT clobber the original startedAt/platform/startOdometer — keep the earlier
        // anchor, only fill a still-null odometer and advance liveness. Not a fresh session ⇒ no
        // inferred-close. The `platform != Unknown` guard is load-bearing: a `_unknown` placeholder
        // (synthesized for a pre-DASH_START event, possibly PERSISTED and rehydrated across a batch
        // boundary and thus carrying started=true) must fall through to the upgrade arm below, or the
        // real platform/startedAt would be discarded and the session would stay `_unknown` forever.
        if (context != null && context.sessionId == sid && context.started && context.platform != Platform.Unknown) {
            return FoldOutcome(
                context = context.copy(
                    startOdometer = context.startOdometer ?: odo,
                    lastEventAt = maxOf(context.lastEventAt, e.occurredAt),
                    lastOdometer = odo ?: context.lastOdometer,
                    // Already a real start — keep its original startSource; only fill if somehow null.
                    startSource = context.startSource ?: p.source,
                ),
            )
        }
        // A placeholder synthesized for a session-scoped event ordered just ahead of DASH_START (in
        // the same batch OR persisted and rehydrated in a later one) is UPGRADED here with the real
        // platform/startedAt/startOdometer; its accumulated counters and liveness carry over. This
        // still counts as a fresh session (its first true start).
        if (context != null && context.sessionId == sid) {
            return FoldOutcome(
                context = context.copy(
                    platform = platform,
                    startedAt = p.startedAt,
                    startOdometer = context.startOdometer ?: odo,
                    lastEventAt = maxOf(context.lastEventAt, e.occurredAt),
                    lastOdometer = odo ?: context.lastOdometer,
                    started = true,
                    startSource = p.source,
                ),
                freshSession = true,
            )
        }
        return FoldOutcome(
            context = SessionFoldContext(
                sessionId = sid,
                platform = platform,
                startedAt = p.startedAt,
                lastEventAt = e.occurredAt,
                startOdometer = odo,
                lastOdometer = odo,
                started = true,
                startSource = p.source,
            ),
            freshSession = true,
        )
    }

    private fun foldOffer(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? OfferPayload
            ?: return FoldOutcome(context = context, skip = "${e.type}: missing/malformed payload")
        val sid = e.sessionId
        val ctx = sid?.let { resolveContext(context, it, e.occurredAt) }
        val platformWire = ctx?.platform?.wire ?: Platform.Unknown.wire
        val eval = p.evaluation
        val parsed = p.parsedOffer
        // Per-mile fuel/non-fuel split from the SAME frozen evaluation the cpm comes from: the eval
        // carries route-total estimates + distance, so per-mile = estimate ÷ distanceMiles. Guard a
        // non-positive distance → null split (the delivery falls back to the 3-step waterfall). By
        // construction fuelPerMile + nonFuelPerMile ≈ operatingCostPerMile (both totals are that same
        // per-mile rate × distance), the invariant the waterfall relies on (#659).
        val evalDist = eval?.distanceMiles
        val fuelPerMile = if (eval != null && evalDist != null && evalDist > 0.0) eval.fuelCostEstimate / evalDist else null
        val nonFuelPerMile = if (eval != null && evalDist != null && evalDist > 0.0) eval.nonFuelCostEstimate / evalDist else null
        val offer = OfferFold(
            eventSequenceId = event.sequenceId,
            sessionId = sid,
            platform = platformWire,
            offerHash = p.offerHash,
            outcome = e.type.name,
            presentedAt = p.presentedAt,
            decidedAt = p.decidedAt,
            payAmount = parsed.payAmount,
            distanceMiles = parsed.distanceMiles,
            itemCount = parsed.itemCount,
            merchantName = eval?.merchantName ?: parsed.orders.firstOrNull()?.storeName,
            score = eval?.score,
            action = eval?.action?.name,
            quality = eval?.qualityLevel?.name,
            estNetPay = eval?.netPayAmount,
            estDollarsPerHour = eval?.dollarsPerHour,
            estDollarsPerMile = eval?.dollarsPerMile,
            estTimeMinutes = eval?.estimatedTimeMinutes,
            estOperatingCostPerMile = eval?.operatingCostPerMile,
            estFuelPerMile = fuelPerMile,
            estNonFuelPerMile = nonFuelPerMile,
        )
        val newCtx = ctx?.let {
            it.advance(e.occurredAt, event.metadata?.odometer)
                .bumpOutcome(e.type)
                // Capture the session-uniform frozen cpm AND its fuel/non-fuel split from any closing
                // offer that carries an evaluation (accepted/declined/timeout all reflect the same
                // economy). Keep-prior on a null (a distance-0 offer captures cpm but no split) so a
                // later good split isn't wiped — session-uniformity makes them numerically identical.
                .copy(
                    lastEvaluatedCostPerMile = eval?.operatingCostPerMile ?: it.lastEvaluatedCostPerMile,
                    lastEvaluatedFuelPerMile = fuelPerMile ?: it.lastEvaluatedFuelPerMile,
                    lastEvaluatedNonFuelPerMile = nonFuelPerMile ?: it.lastEvaluatedNonFuelPerMile,
                )
        }
        return FoldOutcome(context = newCtx, offer = offer)
    }

    private fun foldDeliveryCompleted(
        event: SequencedAppEvent,
        context: SessionFoldContext?,
        currentCostPerMile: Double?,
    ): FoldOutcome {
        val e = event.event
        val p = e.payload as? DeliveryPayload
            ?: return FoldOutcome(context = context, skip = "DELIVERY_COMPLETED: missing/malformed payload")
        val sid = e.sessionId
        val ctx = sid?.let { resolveContext(context, it, e.occurredAt) }
        val platformWire = ctx?.platform?.wire ?: Platform.Unknown.wire
        val odo = event.metadata?.odometer
        val completedAt = p.completedAt ?: e.occurredAt

        // #653: a full-receipt stamp on an IDENTITY-LESS drop of an ALREADY-delivered
        // (multi-delivery) job is SUSPECT. The signal the fold can see: this jobId already
        // delivered ≥1 drop this session (it is in the accumulated/hydrated `deliveredJobIds`),
        // this completion carries the whole `parsedPay` receipt with NO apportioned
        // `dropRealizedPay` share, AND it has no customer identity (both hashes null — the
        // #498/#517/#518 phantom class by construction; the payload copies the task's null hashes,
        // and identity-less drops are excluded from the apportion denominator upstream). Its
        // sibling drops' shares already sum to that receipt, so stamping the full receipt here
        // would double-count the period SUM. Drop the pay/tip/base rather than inflating.
        // The identity discriminator is LOAD-BEARING for history: the pre-#528 mint shape for a
        // legitimate receipted multi-drop stack was N−1 null-pay rows + ONE identity-BEARING row
        // carrying the whole receipt (the job's only money, usually folding LAST) — that row must
        // keep RECEIPT_TOTAL on the v3 refold, not be nulled. A single-delivery job (this jobId's
        // first/only drop) is likewise NOT flagged — a bare totalPay on the sole drop is correct.
        // Defense-in-depth behind the EffectMap identity firewall (which blocks new phantoms).
        val priorDeliveryForThisJob = ctx != null && p.jobId in ctx.deliveredJobIds
        val suspectFullReceipt =
            priorDeliveryForThisJob && p.parsedPay != null && p.dropRealizedPay == null &&
                p.customerHash == null && p.addressHash == null

        // #691 receipt-evidence predicate: a completion carries PAY-BEARING receipt evidence iff it
        // has an itemized receipt OR a POSITIVE bare total. `totalPay` is coerced from the pay screen
        // by `ParsedFieldsFactory.buildPostTask` as `f.double("totalPay") ?: 0.0`, so a transient
        // `delivery_summary_collapsed` frame that fails to parse its final value produces a $0.00
        // `PostTaskFields` — a pseudo-receipt. A real $0 delivery receipt isn't a thing, so a 0.0
        // total is NOT receipt evidence and must not suppress the offer-pay estimate. The same
        // predicate gates stamp-suppression at the EffectMap edge (one definition, two sites).
        val payBearingReceipt = p.parsedPay != null || (p.totalPay ?: 0.0) > 0.0

        // #691 mixed-receipt guard: an offer-pay ESTIMATE (an equal split of the offer total)
        // assumes the WHOLE job is receipt-less. If a sibling drop of this job already folded a REAL
        // receipt this session, mixing the two over-counts invisibly (the reported-total
        // reconciliation floors at 0), so a receipt-less drop of such a job keeps NONE. Defense-in-
        // depth behind the EffectMap job-scoped stamp condition (which is the primary control).
        // FIX 4: a null session context fails CLOSED — with no `receiptedJobIds` to consult we cannot
        // rule out a sibling receipt, so an OFFER_PAY estimate is withheld (matching the suspect
        // check's degraded-context under-count direction — never over-count on missing context).
        val jobAlreadyReceipted = ctx != null && p.jobId in ctx.receiptedJobIds
        val useOfferShare = ctx != null && !suspectFullReceipt &&
            p.dropRealizedPay == null && !payBearingReceipt &&
            p.offerPayShare != null && !jobAlreadyReceipted

        // Pay + basis. dropRealizedPay is a per-drop share (possibly of a multi-drop receipt); its
        // absence with a bare totalPay means the whole receipt landed on this one drop. With neither,
        // the #691 offer-pay estimate is the fallback before NONE. OFFER_PAY is evaluated ABOVE
        // RECEIPT_TOTAL so a STAMPED estimate on a $0-coerced pseudo-receipt folds as the estimate,
        // not as $0.00 — but ONLY when `offerPayShare` is present (null on ALL pre-#691 events), so a
        // historical $0-total row with no stamp still folds RECEIPT_TOTAL $0.00 byte-for-byte (no
        // PROJECTOR_VERSION bump; the un-stamped $0-pseudo-receipt residual rides to #703).
        val realizedPay = when {
            suspectFullReceipt -> null
            p.dropRealizedPay != null -> p.dropRealizedPay
            useOfferShare -> p.offerPayShare
            p.totalPay != null -> p.totalPay
            else -> null
        }
        val payBasis = when {
            suspectFullReceipt -> PayBasis.SUSPECT_FULL_RECEIPT
            p.dropRealizedPay != null -> PayBasis.DROP_SHARE
            useOfferShare -> PayBasis.OFFER_PAY
            p.totalPay != null -> PayBasis.RECEIPT_TOTAL
            else -> PayBasis.NONE
        }
        // tip/basePay only when this drop IS the job's sole drop — i.e. it carries the WHOLE receipt.
        // The apportioner stamps `dropRealizedPay` even on a single drop (its share == the total), so
        // a null-drop bare receipt OR a share equal to the receipt total is the sole-drop signal; a
        // smaller share is a stacked drop, which has no per-drop tip split yet (→ null). A suspect
        // full-receipt drop (#653) is never the sole drop — its siblings already carry the receipt.
        val receipt = p.parsedPay
        val soleDrop = !suspectFullReceipt && receipt != null &&
            (p.dropRealizedPay == null || kotlin.math.abs(p.dropRealizedPay - receipt.total) < 0.005)
        val tip = if (soleDrop) receipt?.totalTip else null
        val basePay = if (soleDrop) receipt?.totalBasePay else null

        // Partition deltas. Anchor = the previous completion (or DASH_START) in the same session.
        // Floor the per-row delta at 0 like the session-level SUM (MAX(…,0)): a mid-session odometer
        // reset yields a NEGATIVE delta, which would otherwise INFLATE this row's netProfit
        // (pay − negativeMiles × cpm). The next drop re-anchors off this (lower) reading naturally.
        val prevOdo = ctx?.prevDropOdometer ?: ctx?.startOdometer
        val realizedMiles = if (odo != null && prevOdo != null) (odo - prevOdo).coerceAtLeast(0.0) else null
        val prevAt = ctx?.prevDropAt ?: ctx?.startedAt
        val realizedMinutes = if (prevAt != null) (completedAt - prevAt) / 60_000.0 else null

        // Frozen economy — immutable historical fact.
        val (frozenCpm, costBasis) = when {
            ctx?.lastEvaluatedCostPerMile != null -> ctx.lastEvaluatedCostPerMile to CostBasis.OFFER_FROZEN
            currentCostPerMile != null -> currentCostPerMile to CostBasis.CURRENT_FALLBACK
            else -> null to CostBasis.NONE
        }
        // The fuel/non-fuel split is populated ONLY off the OFFER_FROZEN basis — the offer's own
        // evaluation carried it. CURRENT_FALLBACK/NONE deliveries have no split (the live economy read
        // supplies a bare cpm, not its components), so they stay null → the 4-step waterfall falls back
        // to 3-step for those rows (#659). By construction frozenFuelPerMile + frozenNonFuelPerMile ≈
        // frozenCostPerMile when all present.
        val frozenFuelPerMile = if (costBasis == CostBasis.OFFER_FROZEN) ctx?.lastEvaluatedFuelPerMile else null
        val frozenNonFuelPerMile = if (costBasis == CostBasis.OFFER_FROZEN) ctx?.lastEvaluatedNonFuelPerMile else null
        val netProfit = if (frozenCpm != null && realizedPay != null && realizedMiles != null) {
            NetProfit.net(realizedPay, realizedMiles, frozenCpm)
        } else {
            null
        }

        val delivery = DeliveryFold(
            eventSequenceId = event.sequenceId,
            sessionId = sid,
            platform = platformWire,
            jobId = p.jobId,
            taskId = p.taskId,
            storeName = p.storeName,
            customerHash = p.customerHash,
            addressHash = p.addressHash,
            phaseStartedAt = p.phaseStartedAt,
            arrivedAt = p.arrivedAt,
            completedAt = completedAt,
            deadlineMillis = p.deadlineMillis,
            realizedPay = realizedPay,
            payBasis = payBasis,
            tip = tip,
            cashTip = null, // machine completions never carry a driver cash tip (#688)
            basePay = basePay,
            odometerAtCompletion = odo,
            realizedMiles = realizedMiles,
            realizedMinutes = realizedMinutes,
            frozenCostPerMile = frozenCpm,
            frozenFuelPerMile = frozenFuelPerMile,
            frozenNonFuelPerMile = frozenNonFuelPerMile,
            netProfit = netProfit,
            costBasis = costBasis,
            // #159 B1/B2: persist the FULL receipt store-form set on the one completion that carries
            // parsedPay (null on receipt-less sibling drops), so resolution reads running keys from
            // ROWS — every store of a multi-store stack stays keyed even after a payout-less re-run.
            payoutStoreForms = p.parsedPay?.customerTips
                ?.mapNotNull { it.type.takeIf { t -> t.isNotBlank() } }
                ?.takeIf { it.isNotEmpty() },
            jobOfferHashes = p.jobOfferHashes,
        )

        // #691: mark the job receipted once any drop folds RECEIPT EVIDENCE, so a later receipt-less
        // sibling of the same job is denied the offer-pay estimate (the mixed-receipt guard above).
        // Includes SUSPECT_FULL_RECEIPT (#653): its money was nulled, but the receipt still HAPPENED,
        // so it proves the job is not receipt-less. SSOT with the DAO hydration IN-list.
        val jobReceipted = payBasis in PayBasis.RECEIPT_EVIDENCE
        val newCtx = ctx?.copy(
            deliveries = ctx.deliveries + 1,
            deliveredJobIds = ctx.deliveredJobIds + p.jobId,
            receiptedJobIds = if (jobReceipted) ctx.receiptedJobIds + p.jobId else ctx.receiptedJobIds,
            // Advance the miles anchor only when this drop had an odometer, so a null-odometer drop
            // folds its miles into the next drop instead of resetting the partition; the time anchor
            // always advances (time is always known).
            prevDropOdometer = odo ?: ctx.prevDropOdometer,
            prevDropAt = completedAt,
            lastEventAt = maxOf(ctx.lastEventAt, e.occurredAt),
            lastOdometer = odo ?: ctx.lastOdometer,
        )
        // #159: every DELIVERY_COMPLETED re-triggers store resolution for its job (job-scoped). The
        // payout is NOT threaded — resolution reads payoutStoreForms from the committed rows (B1).
        val resolution = StoreResolution(
            sessionId = sid,
            platform = platformWire,
            jobId = p.jobId,
            offerHashes = p.jobOfferHashes,
            at = e.occurredAt,
        )
        return FoldOutcome(context = newCtx, delivery = delivery, resolution = resolution)
    }

    /**
     * A completed pickup (#159) → one [PickupFold] visits row. `PICKUP_CONFIRMED` carries the full
     * `phaseStartedAt/arrivedAt/confirmedAt` progression; `storeKey` is stamped later by resolution.
     * Advances liveness like any session-attributed event (the pre-#159 `else` branch behaviour).
     */
    private fun foldPickupConfirmed(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? PickupPayload
            ?: return FoldOutcome(context = context, skip = "PICKUP_CONFIRMED: missing/malformed payload")
        val sid = e.sessionId
        val ctx = sid?.let { resolveContext(context, it, e.occurredAt) }
        val platformWire = ctx?.platform?.wire ?: Platform.Unknown.wire
        val pickup = PickupFold(
            eventSequenceId = event.sequenceId,
            sessionId = sid,
            platform = platformWire,
            jobId = p.jobId,
            taskId = p.taskId,
            storeName = p.storeName,
            phaseStartedAt = p.phaseStartedAt,
            arrivedAt = p.arrivedAt,
            confirmedAt = p.confirmedAt ?: e.occurredAt,
            deadlineMillis = p.deadlineMillis,
            activity = p.activity,
            storeAddress = p.storeAddress,
            jobOfferHashes = p.jobOfferHashes,
        )
        val newCtx = ctx?.advance(e.occurredAt, event.metadata?.odometer)
        return FoldOutcome(context = newCtx, pickup = pickup)
    }

    /**
     * A driver-entered missed delivery (#650) → one `DeliveryFold` (payBasis [PayBasis.MANUAL]),
     * folded into the session accumulator exactly as a captured completion, but from the driver's own
     * statement. The frozen economy is inherited exactly like [foldDeliveryCompleted] (the session's
     * offer basis, else the current fallback, else NONE) and is likewise immutable — but the NET uses
     * the MANUAL missing-terms-as-0 policy (net-additive; see the inline comment): an uncosted driver
     * statement must not lose its dollars from period net just for being recorded.
     */
    private fun foldManualDelivery(
        event: SequencedAppEvent,
        context: SessionFoldContext?,
        currentCostPerMile: Double?,
    ): FoldOutcome {
        val e = event.event
        val p = e.payload as? ManualDeliveryPayload
            ?: return FoldOutcome(context = context, skip = "MANUAL_DELIVERY: missing/malformed payload")
        val sid = e.sessionId ?: p.sessionId
        // resolveContext synthesizes an `_unknown` context if the session isn't known — and the
        // projector upserts every touched context in the same batch transaction, so a manual delivery
        // can never create a dangling-sessionId delivery row (the #661 invariant).
        val ctx = resolveContext(context, sid, e.occurredAt)
        val jobId = "manual:" + event.sequenceId

        // Frozen economy — the same waterfall as a captured completion (immutable historical fact).
        val (frozenCpm, costBasis) = when {
            ctx.lastEvaluatedCostPerMile != null -> ctx.lastEvaluatedCostPerMile to CostBasis.OFFER_FROZEN
            currentCostPerMile != null -> currentCostPerMile to CostBasis.CURRENT_FALLBACK
            else -> null to CostBasis.NONE
        }
        val frozenFuelPerMile = if (costBasis == CostBasis.OFFER_FROZEN) ctx.lastEvaluatedFuelPerMile else null
        val frozenNonFuelPerMile = if (costBasis == CostBasis.OFFER_FROZEN) ctx.lastEvaluatedNonFuelPerMile else null
        // MANUAL-basis net policy (#650 review F1): missing cost terms count as 0, so an uncosted
        // driver statement stays NET-ADDITIVE — exactly as its dollars were while still inside
        // `unattributedPay` (the documented net-additive estimate, PeriodEconomics). Without this,
        // recording the missed delivery the callout asks for would DROP period net by the row's pay:
        // the money leaves the net-additive unattributed bucket and lands in a null-net row that
        // SUM(netProfit) discards. Machine rows keep machine semantics (the null-net machine seam is
        // #660-family and deliberately not widened here).
        val netProfit = NetProfit.net(p.pay, p.miles ?: 0.0, frozenCpm ?: 0.0)

        val delivery = DeliveryFold(
            eventSequenceId = event.sequenceId,
            sessionId = sid,
            platform = ctx.platform.wire,
            jobId = jobId,
            taskId = jobId,
            storeName = p.storeName,
            customerHash = null,
            addressHash = null,
            phaseStartedAt = p.completedAt,
            arrivedAt = null,
            completedAt = p.completedAt,
            deadlineMillis = null,
            realizedPay = p.pay,
            payBasis = PayBasis.MANUAL,
            tip = p.tip,
            cashTip = p.cashTip, // driver-entered cash tip (#688) — the only fold that writes it
            basePay = null,
            odometerAtCompletion = null,
            // The driver's own stated miles — NOT a partition delta off the odometer.
            realizedMiles = p.miles,
            realizedMinutes = null,
            frozenCostPerMile = frozenCpm,
            frozenFuelPerMile = frozenFuelPerMile,
            frozenNonFuelPerMile = frozenNonFuelPerMile,
            netProfit = netProfit,
            costBasis = costBasis,
        )

        // Advance the delivery counters + liveness, but DO NOT touch prevDropOdometer/prevDropAt and
        // do not pass the event's odometer into them: a manual delivery carries no odometer reading and
        // must not perturb the surrounding machine rows' partition (miles/time) deltas on a refold —
        // that would make the rebuild non-faithful. This non-perturbation is load-bearing.
        // Liveness advances with the DELIVERY's stated time, never the correction's wall-clock
        // `occurredAt` (#650 review F2): a correction recorded days later must not stretch a
        // crash-orphaned (endedAt-null) session's online span to "now".
        val newCtx = ctx.copy(
            deliveries = ctx.deliveries + 1,
            deliveredJobIds = ctx.deliveredJobIds + jobId,
            lastEventAt = maxOf(ctx.lastEventAt, p.completedAt),
        )
        return FoldOutcome(context = newCtx, delivery = delivery)
    }

    /**
     * A driver PAY_ADJUSTMENT (#650) — the pure fold emits the [PayAdjustmentFold] decision (which row,
     * what new pay); it CANNOT read the target `delivery_record` here (that is not the session
     * accumulator), so the orchestrator applies the re-price inside the batch transaction. The session
     * context passes through UNTOUCHED (#650 review F2): a re-price is bookkeeping about a past row,
     * not session activity — advancing liveness with the CORRECTION's wall-clock `occurredAt` would
     * stretch a crash-orphaned (endedAt-null) session's online span to "now", and synthesizing a
     * context here could mint a session row stamped with correction time (the target's session row
     * already exists via the #661 invariant, or its delivery row could not).
     */
    private fun foldPayAdjustment(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? PayAdjustmentPayload
            ?: return FoldOutcome(context = context, skip = "PAY_ADJUSTMENT: missing/malformed payload")
        return FoldOutcome(
            context = context,
            payAdjustment = PayAdjustmentFold(p.targetEventSequenceId, p.newPay),
        )
    }

    /**
     * A driver DELIVERY_ADJUSTMENT (#688) — the widened analog of [foldPayAdjustment]. The pure fold
     * emits the [DeliveryAdjustmentFold] decision (which row, which fields); it CANNOT read the target
     * `delivery_record` here, so the orchestrator applies the multi-field re-apply inside the batch
     * transaction. The session context passes through UNTOUCHED (the #650 review F2 discipline): a
     * re-apply is bookkeeping about a past row, not session activity — advancing liveness with the
     * correction's wall-clock `occurredAt` would stretch a crash-orphaned session's online span.
     */
    private fun foldDeliveryAdjustment(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? DeliveryAdjustmentPayload
            ?: return FoldOutcome(context = context, skip = "DELIVERY_ADJUSTMENT: missing/malformed payload")
        return FoldOutcome(
            context = context,
            deliveryAdjustment = DeliveryAdjustmentFold(
                targetEventSequenceId = p.targetEventSequenceId,
                newStoreName = p.newStoreName,
                newPay = p.newPay,
                newTip = p.newTip,
                newCashTip = p.newCashTip,
                newMiles = p.newMiles,
            ),
        )
    }

    private fun foldDashStop(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? SessionStopPayload
            ?: return FoldOutcome(context = context, skip = "DASH_STOP: missing/malformed payload")
        val sid = e.sessionId ?: p.sessionId
            ?: return FoldOutcome(context = context, skip = "DASH_STOP: no sessionId")
        val ctx = resolveContext(context, sid, e.occurredAt, platformName = p.platform)
        val odo = event.metadata?.odometer
        val newCtx = ctx.copy(
            endedAt = p.endedAt,
            endSource = p.source,
            reportedEarnings = p.totalEarnings ?: ctx.reportedEarnings,
            reportedDurationMillis = p.sessionDurationMillis ?: ctx.reportedDurationMillis,
            lastEventAt = maxOf(ctx.lastEventAt, e.occurredAt),
            lastOdometer = odo ?: ctx.lastOdometer,
        )
        // #159 F3: a session close re-resolves every job of the session (jobId null ⇒ session-level).
        // Row-sourced resolution makes this payout-less trigger recompute the SAME keys, never a
        // downgrade (B1). The offer link uses the temporal fallback here (no offer hashes on the stop).
        val resolution = StoreResolution(
            sessionId = sid,
            platform = newCtx.platform.wire,
            jobId = null,
            offerHashes = emptyList(),
            at = e.occurredAt,
        )
        return FoldOutcome(context = newCtx, resolution = resolution)
    }

    /**
     * The in-memory/hydrated context for [sid], or a synthesized `_unknown`-platform context when a
     * session-scoped event has no known DASH_START (fold started mid-session past the watermark, or
     * a lost start). A synthesized session degrades to a null-odometer / _unknown-platform row —
     * never a wrong one. [platformName] refines an `_unknown` platform when the event itself carries
     * one (DASH_STOP's #314 stamp) — whether that `_unknown` context is being synthesized here for
     * the first time, or was already synthesized by an EARLIER event in the session and is only now
     * being hydrated/matched by [sid] (a skipped/malformed DASH_START leaves the session `_unknown`
     * until its DASH_STOP arrives with the real platform; without this, the upgrade path only fired
     * for a brand-new context and an already-`_unknown` session stayed `_unknown` forever). Never
     * clobbers a REAL platform — only an `Unknown` one is eligible — and a [platformName] that itself
     * fails to resolve (or resolves back to [Platform.Unknown]) leaves the context untouched.
     */
    private fun resolveContext(
        context: SessionFoldContext?,
        sid: String,
        occurredAt: Long,
        platformName: String? = null,
    ): SessionFoldContext {
        val existing = context?.takeIf { it.sessionId == sid }
        if (existing != null) {
            if (existing.platform == Platform.Unknown && platformName != null) {
                val resolved = Platform.fromName(platformName)
                if (resolved != null && resolved != Platform.Unknown) {
                    return existing.copy(platform = resolved)
                }
            }
            return existing
        }
        return SessionFoldContext(
            sessionId = sid,
            platform = platformName?.let { Platform.fromName(it) } ?: Platform.Unknown,
            startedAt = occurredAt,
            lastEventAt = occurredAt,
        )
    }

    private fun SessionFoldContext.advance(occurredAt: Long, odometer: Double?): SessionFoldContext =
        copy(
            lastEventAt = maxOf(lastEventAt, occurredAt),
            lastOdometer = odometer ?: lastOdometer,
        )

    private fun SessionFoldContext.bumpOutcome(type: AppEventType): SessionFoldContext = when (type) {
        AppEventType.OFFER_ACCEPTED -> copy(offersAccepted = offersAccepted + 1)
        AppEventType.OFFER_DECLINED -> copy(offersDeclined = offersDeclined + 1)
        AppEventType.OFFER_TIMEOUT -> copy(offersTimeout = offersTimeout + 1)
        else -> this
    }
}
