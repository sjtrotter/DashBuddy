package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.evaluation.NetProfit
import cloud.trotter.dashbuddy.domain.model.event.SequencedAppEvent
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryAdjustmentPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliverySessionAssignPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.ManualDeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PayAdjustmentPayload

/**
 * The driver-correction folds, split out of [RecordFolds] (#761, the #237 file-ceiling residue;
 * [LegFolds] is the same-package precedent). Pure — no Android / DB / wall clock. Covers the manual
 * missed-delivery mint ([foldManualDelivery]) and the three decision-only re-apply folds
 * ([foldPayAdjustment] / [foldDeliveryAdjustment] / [foldDeliverySessionAssign]) whose actual row
 * writes the orchestrator performs inside the batch transaction. The record-mint folds live in
 * [DeliveryFolds]; [RecordFolds] keeps the dispatcher, session-lifecycle folds, and — the ONE
 * top-level definition both new files derive from — the shared session helpers [resolveContext] /
 * [advance].
 */
internal object CorrectionFolds {

    /**
     * A driver-entered missed delivery (#650) → one `DeliveryFold` (payBasis [PayBasis.MANUAL]),
     * folded into the session accumulator exactly as a captured completion, but from the driver's own
     * statement. The frozen economy is inherited exactly like [foldDeliveryCompleted] (the session's
     * offer basis, else the current fallback, else NONE) and is likewise immutable — but the NET uses
     * the MANUAL missing-terms-as-0 policy (net-additive; see the inline comment): an uncosted driver
     * statement must not lose its dollars from period net just for being recorded.
     */
    fun foldManualDelivery(
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
    fun foldPayAdjustment(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
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
    fun foldDeliveryAdjustment(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
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

    /**
     * A driver DELIVERY_SESSION_ASSIGN (#660 piece 2) — the pure fold emits the [SessionAssignFold]
     * decision (which row, which target session / unassign); it CANNOT read/guard the target
     * `delivery_record` here, so the orchestrator applies the re-attribution (with its fail-closed
     * guards) inside the batch transaction. The session context passes through UNTOUCHED (the #650
     * review F2 liveness discipline, identical to [foldPayAdjustment]/[foldDeliveryAdjustment]): a
     * categorize recorded days later must not stretch a crash-orphaned session's online span, and it
     * must not synthesize a context (the target session row already exists — the UI can only offer an
     * ENDED session that folded a DASH_STOP, and the orphan's own delivery row exists too).
     */
    fun foldDeliverySessionAssign(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? DeliverySessionAssignPayload
            ?: return FoldOutcome(context = context, skip = "DELIVERY_SESSION_ASSIGN: missing/malformed payload")
        return FoldOutcome(
            context = context,
            sessionAssign = SessionAssignFold(p.targetEventSequenceId, p.newSessionId),
        )
    }
}
