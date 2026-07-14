package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.SequencedAppEvent
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
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
    /**
     * Machine-computed to-store driving leg (#688 phase B) — the claimed [PendingStoreLeg]'s miles for
     * this drop's job (exact NORMALIZED-chain store-form match, else FIFO within the job, else null).
     * Stamped ONLY on a leg-sum row (`milesToDropoff != null`); a LEGACY row (missed arrival) stamps
     * null and instead retires the SESSION's already-closed store legs — any job, since the legacy span
     * is a session-level partition delta (#688 review Fix 1/Fix 4 + re-verify widening) — so a lone
     * store leg never rides an otherwise-untouched legacy row. Provenance only: a driver `newMiles`
     * correction rewrites [realizedMiles] but NEVER these leg columns, so `milesToStore + milesToDropoff`
     * may then ≠ `realizedMiles` — that inequality is the visible edit trail. Null on a
     * receipt-less/anchorless/legacy leg and all pre-phase-B history.
     */
    val milesToStore: Double? = null,
    /**
     * Machine-computed to-dropoff driving leg (#688 phase B) — this drop's own `taskId` entry in the
     * pending dropoff legs (unambiguous). Null when no odometer-bearing `DELIVERY_ARRIVED` preceded the
     * completion (missed arrival, phantom-class completion, all pre-phase-B history). When non-null,
     * `realizedMiles == (milesToStore ?: 0) + milesToDropoff` (the leg-sum rule, §2); when null the row
     * keeps the legacy partition delta.
     */
    val milesToDropoff: Double? = null,
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
    /**
     * A driver DELIVERY_SESSION_ASSIGN decision (#660 piece 2): the pure fold decides WHICH row and
     * WHICH session (null ⇒ unassign), but cannot read/guard the target `delivery_record` here — the
     * orchestrator applies the re-attribution (with its fail-closed guards) inside the batch transaction.
     */
    val sessionAssign: SessionAssignFold? = null,
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
 * A driver's session-(re)attribution decision (#660 piece 2) — the pure fold's output for a
 * DELIVERY_SESSION_ASSIGN. The orchestrator looks up [targetEventSequenceId] in `delivery_records` and,
 * subject to its fail-closed guards (movable-rows-only, real ENDED target session, platform coherence,
 * cash-bearing-unassign block), rewrites ONLY the row's `sessionId` (→ [newSessionId]; null ⇒ unassign
 * back to the "(No session)" bucket) and its `sessionAssigned` marker — never any frozen economy column.
 * Because a DELIVERY_SESSION_ASSIGN always sequences after its target's completion AND after the target
 * session's `DASH_STOP`, a from-zero refold replays them in order and reproduces identical rows/counters.
 * `note` is intentionally absent — it lives only in the event payload and changes no column.
 */
data class SessionAssignFold(
    val targetEventSequenceId: Long,
    val newSessionId: String? = null,
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
            AppEventType.DELIVERY_COMPLETED -> DeliveryFolds.foldDeliveryCompleted(event, context, currentCostPerMile)
            // #688 phase B leg-anchor events (each also keeps the pre-existing liveness advance) —
            // the leg machinery lives in LegFolds (P3 file split, #688 review Fix 6).
            AppEventType.PICKUP_ARRIVED -> LegFolds.foldPickupArrived(event, context)
            AppEventType.DELIVERY_ARRIVED -> LegFolds.foldDeliveryArrived(event, context)
            AppEventType.DELIVERY_CONFIRMED -> LegFolds.foldDeliveryConfirmed(event, context)
            // #736 unassign-via-help (#688 review Fix 2): a leg-state-only arm — purge the abandoned
            // task's pending legs so a surviving sibling can't inherit them, liveness advanced exactly
            // as the old catch-all `else` arm did (read-model row-inert; no record minted).
            AppEventType.TASK_UNASSIGNED -> LegFolds.foldTaskUnassigned(event, context)
            AppEventType.PICKUP_CONFIRMED -> DeliveryFolds.foldPickupConfirmed(event, context)
            AppEventType.MANUAL_DELIVERY -> CorrectionFolds.foldManualDelivery(event, context, currentCostPerMile)
            AppEventType.PAY_ADJUSTMENT -> CorrectionFolds.foldPayAdjustment(event, context)
            AppEventType.DELIVERY_ADJUSTMENT -> CorrectionFolds.foldDeliveryAdjustment(event, context)
            AppEventType.DELIVERY_SESSION_ASSIGN -> CorrectionFolds.foldDeliverySessionAssign(event, context)
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
                    // #688: keep the running leg anchor; only fill it if the placeholder had none.
                    legState = context.legState.copy(prevLegOdometer = context.legState.prevLegOdometer ?: odo),
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
                    // #688: the placeholder's counters/liveness carry over; seed the leg anchor if unset.
                    legState = context.legState.copy(prevLegOdometer = context.legState.prevLegOdometer ?: odo),
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
                // #688: initialize the leg anchor to the start odometer (null ⇒ first leg unknown).
                legState = LegState(prevLegOdometer = odo),
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
        )
        return FoldOutcome(context = newCtx, resolution = resolution)
    }

    private fun SessionFoldContext.bumpOutcome(type: AppEventType): SessionFoldContext = when (type) {
        AppEventType.OFFER_ACCEPTED -> copy(offersAccepted = offersAccepted + 1)
        AppEventType.OFFER_DECLINED -> copy(offersDeclined = offersDeclined + 1)
        AppEventType.OFFER_TIMEOUT -> copy(offersTimeout = offersTimeout + 1)
        else -> this
    }
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
 *
 * `internal` top-level (not a `RecordFolds` member) so [LegFolds]'s leg-anchor handlers share the ONE
 * definition (#688 review Fix 6 file split) — a duplicate would be an SSOT divergence bug.
 */
internal fun resolveContext(
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

/**
 * Advance liveness (open-session duration + inferred-close + odometer anchors). `internal` top-level so
 * both [RecordFolds] and [LegFolds] route through one definition (#688 review Fix 6).
 */
internal fun SessionFoldContext.advance(occurredAt: Long, odometer: Double?): SessionFoldContext =
    copy(
        lastEventAt = maxOf(lastEventAt, occurredAt),
        lastOdometer = odometer ?: lastOdometer,
    )
