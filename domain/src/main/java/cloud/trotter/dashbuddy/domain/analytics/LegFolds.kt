package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.model.event.SequencedAppEvent
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.TaskUnassignedPayload
import cloud.trotter.dashbuddy.domain.state.TaskPhase

/**
 * The #688 phase-B per-leg mileage machinery, split out of [RecordFolds] (#688 review Fix 6, P3 file
 * ceiling). Pure — no Android / DB / wall clock — driven only by the event's own `metadata.odometer`
 * stamps and threaded [SessionFoldContext]. The leg-anchor-only lifecycle events (`PICKUP_ARRIVED` /
 * `DELIVERY_ARRIVED` / `DELIVERY_CONFIRMED`) and the `TASK_UNASSIGNED` leg purge live here; the
 * `DELIVERY_COMPLETED` **consumption** side (which store leg a drop claims, the legacy-basis retire)
 * stays in [DeliveryFolds.foldDeliveryCompleted] (#761 split) with the rest of the record mint. Shared session
 * helpers ([resolveContext] / [advance]) are the ONE top-level definition in `RecordFolds.kt`.
 */
internal object LegFolds {

    /**
     * `PICKUP_ARRIVED` (#688 phase B) — closes a **to-store** leg (previous anchor → this arrival) and
     * queues it for the job's completion to claim as `milesToStore`. Keeps the pre-existing liveness
     * advance. A malformed/payload-less event advances liveness only (leg anchor unchanged, miles roll
     * forward — never lost, at worst lumped like today).
     */
    fun foldPickupArrived(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val sid = e.sessionId ?: return FoldOutcome(context = context)
        val ctx = resolveContext(context, sid, e.occurredAt)
        val odo = event.metadata?.odometer
        val p = e.payload as? PickupPayload
        val withLeg = if (p != null) ctx.closeStoreLeg(p.taskId, p.jobId, p.storeName, odo) else ctx
        return FoldOutcome(context = withLeg.advance(e.occurredAt, odo))
    }

    /**
     * `DELIVERY_ARRIVED` (#688 phase B) — closes a **to-dropoff** leg (previous anchor → this arrival),
     * accumulated onto the drop's own `taskId` (a grace-flap re-arrival adds to the same entry, never a
     * second one). Keeps the pre-existing liveness advance; a malformed event advances liveness only.
     */
    fun foldDeliveryArrived(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val sid = e.sessionId ?: return FoldOutcome(context = context)
        val ctx = resolveContext(context, sid, e.occurredAt)
        val odo = event.metadata?.odometer
        val p = e.payload as? DeliveryPayload
        val withLeg = if (p != null) ctx.addDropoffLeg(p.taskId, odo) else ctx
        return FoldOutcome(context = withLeg.advance(e.occurredAt, odo))
    }

    /**
     * `DELIVERY_CONFIRMED` (#688 phase B) — advances the leg anchor only (the handoff/POD point, before
     * the completion receipt), alongside the pre-existing liveness advance. Produces no record.
     */
    fun foldDeliveryConfirmed(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val sid = e.sessionId ?: return FoldOutcome(context = context)
        val ctx = resolveContext(context, sid, e.occurredAt)
        val odo = event.metadata?.odometer
        val p = e.payload as? DeliveryPayload
        val withAnchor = if (p != null) ctx.copy(legState = ctx.legState.advanceAnchor(odo)) else ctx
        return FoldOutcome(context = withAnchor.advance(e.occurredAt, odo))
    }

    /**
     * `TASK_UNASSIGNED` (#736 × #688 review Fix 2) — a **leg-state-only** arm. The dasher abandoned this
     * task via the pickup help / resolution flow; an unassigned task's miles are session-level ONLY,
     * never per-delivery (the #736 decision), so its pending leg must be PURGED before a surviving
     * sibling's FIFO claim can inherit it. A pickup-phase abandon drops the queued to-store leg keyed to
     * the pickup task (legs are `pickupTaskId`-keyed — set from `PickupPayload.taskId` at
     * `PICKUP_ARRIVED`, the same id the help flow reports); a dropoff-phase abandon drops the to-dropoff
     * leg keyed to this `taskId` (also the future #752 shape). It mints NO record and is read-model
     * row-inert; liveness advances EXACTLY as the old catch-all `else` arm did (identical
     * `lastEventAt`/`lastOdometer`), so session duration/odometer stay byte-identical to pre-fix folds.
     * A malformed/payload-less event degrades to a liveness-only advance (the `else`-arm behaviour).
     */
    fun foldTaskUnassigned(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val sid = e.sessionId ?: return FoldOutcome(context = context)
        val ctx = resolveContext(context, sid, e.occurredAt)
        val odo = event.metadata?.odometer
        val p = e.payload as? TaskUnassignedPayload
            ?: return FoldOutcome(context = ctx.advance(e.occurredAt, odo))
        val ls = ctx.legState
        val cleaned = when (p.phase) {
            TaskPhase.PICKUP ->
                ls.copy(pendingStoreLegs = ls.pendingStoreLegs.filterNot { it.pickupTaskId == p.taskId })
            TaskPhase.DROPOFF ->
                ls.copy(pendingDropoffLegs = ls.pendingDropoffLegs - p.taskId)
        }
        return FoldOutcome(context = ctx.copy(legState = cleaned).advance(e.occurredAt, odo))
    }
}

// ── #688 phase B leg helpers (pure; all mirror the partition-anchor conventions in RecordFolds) ──
// Top-level `internal` extensions so both [DeliveryFolds] (foldPickupConfirmed's anchor advance,
// foldDeliveryCompleted's inline consume) and [LegFolds] share ONE definition (Fix 6 file split).

/**
 * Advance the leg anchor to [odo]. A null odo does NOT advance (same rule as `prevDropOdometer`):
 * the leg anchor stays put and the miles roll forward into the next closed leg.
 */
internal fun LegState.advanceAnchor(odo: Double?): LegState =
    if (odo == null) this else copy(prevLegOdometer = odo)

/**
 * Close the current leg at [odo]: returns the leg miles (floored at 0 — the mid-session
 * odometer-reset defense) and the leg state with its anchor advanced. `null` miles ⇒ unmeasurable:
 * either this event carries no odo (anchor unchanged, miles roll forward) or no anchor was ever
 * seen (anchor takes this odo, this leg is unknown).
 */
internal fun LegState.closeLeg(odo: Double?): Pair<Double?, LegState> = when {
    odo == null -> null to this
    prevLegOdometer == null -> null to copy(prevLegOdometer = odo)
    else -> (odo - prevLegOdometer).coerceAtLeast(0.0) to copy(prevLegOdometer = odo)
}

/** Cap a pending list at [LegState.MAX_PENDING] with deterministic drop-oldest (bounded ingestion). */
private fun <T> List<T>.capPending(): List<T> =
    if (size <= LegState.MAX_PENDING) this else takeLast(LegState.MAX_PENDING)

/**
 * Close a to-store leg and queue it (or accumulate into the same [pickupTaskId] entry on a
 * re-arrival). Bounded at [LegState.MAX_PENDING]. Stamps `closedAtOdometer` = this arrival's [odo]
 * (guaranteed non-null on the `miles != null` branch) so a legacy-basis completion can retire only the
 * store legs that closed BEFORE it (#688 review Fix 1); a re-arrival updates it to the LATEST close.
 */
internal fun SessionFoldContext.closeStoreLeg(
    pickupTaskId: String,
    jobId: String,
    storeName: String?,
    odo: Double?,
): SessionFoldContext {
    val (miles, ls) = legState.closeLeg(odo)
    if (miles == null) return copy(legState = ls)
    val existingIdx = ls.pendingStoreLegs.indexOfFirst { it.pickupTaskId == pickupTaskId }
    val legs = if (existingIdx >= 0) {
        ls.pendingStoreLegs.mapIndexed { i, leg ->
            if (i == existingIdx) leg.copy(miles = leg.miles + miles, closedAtOdometer = odo) else leg
        }
    } else {
        (ls.pendingStoreLegs + PendingStoreLeg(pickupTaskId, jobId, storeName, miles, closedAtOdometer = odo))
            .capPending()
    }
    return copy(legState = ls.copy(pendingStoreLegs = legs))
}

/**
 * Close a to-dropoff leg, accumulating onto the drop's own [taskId] (a re-arrival adds to the same
 * entry). Bounded at [LegState.MAX_PENDING] with drop-oldest by insertion order.
 */
internal fun SessionFoldContext.addDropoffLeg(taskId: String, odo: Double?): SessionFoldContext {
    val (miles, ls) = legState.closeLeg(odo)
    if (miles == null) return copy(legState = ls)
    val existing = ls.pendingDropoffLegs[taskId]
    val merged = if (existing != null) {
        ls.pendingDropoffLegs + (taskId to (existing + miles))
    } else {
        val added = ls.pendingDropoffLegs + (taskId to miles)
        if (added.size <= LegState.MAX_PENDING) {
            added
        } else {
            // Drop-oldest by insertion order (LinkedHashMap preserves it); keep the newest cap.
            added.entries.toList().takeLast(LegState.MAX_PENDING).associate { it.key to it.value }
        }
    }
    return copy(legState = ls.copy(pendingDropoffLegs = merged))
}
