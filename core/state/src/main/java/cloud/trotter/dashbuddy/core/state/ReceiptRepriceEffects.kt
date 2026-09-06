package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.DropPayApportioner
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingReceiptReprice
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.isAccountableDropoff
import timber.log.Timber

/**
 * #1033 layer 2 — the machine's own Tier-1 receipt correction: the DECISION (a pure stepper
 * transition) and its EMISSION (a one-line effect diff), kept in one file because they are one
 * concern.
 *
 * **The seam.** A COLLAPSED post-delivery receipt carries a total but no itemization, so
 * `DropPayApportioner.apportion(parsedPay = null, …)` returns nothing and the `DELIVERY_COMPLETED`
 * it closes is priced by the #691 `OFFER_PAY` estimate. Layer 1 widens the retire grace so the
 * expansion usually lands *before* the commit; this is the other half — when it lands *after*, the
 * itemization is real evidence that arrived late, and the drop is re-priced from it rather than left
 * on an estimate forever.
 *
 * **Append-only, exactly like a driver correction.** No completion event is rewritten: a new
 * `DELIVERY_RECEIPT_REPRICE` per delivered drop of the job carries the receipt + that drop's share
 * (from the SAME [DropPayApportioner.apportion] the mint uses, over the SAME denominator), and the
 * fold re-prices the row in place. Frozen economy is never re-costed — net recomputes against the
 * row's OWN `frozenCostPerMile` at the orchestrator.
 *
 * **Why the decision is in the stepper (review round 4, UDF).** It has to write state as it decides:
 * `PlatformRegion.lastClosedJobReceipt` must learn, atomically, that the job has just been re-priced
 * and what its receipt now totals. An effect diff cannot do that, and the round-3 shape — deciding
 * in `EffectMap` off a marker nobody could update — is exactly why a re-priced job could be re-priced
 * back DOWN by a later same-total receipt from a different job. So [decideReceiptReprice] runs inside
 * `updateSessionFields`, parks its result on [PlatformRegion.pendingReceiptReprice] (a one-step
 * handoff), and [diffReceiptReprice] just reports it.
 *
 * **Ownership** is the other thing the rounds taught. `lastAnnouncedPostTaskTaskId` falls back to
 * `recentTasks.lastOrNull()`, so a receipt with no job of its own attaches to whatever drop happened
 * to finish last. Absence of a live job is NOT evidence of ownership — acceptance and the job mint
 * are separate transitions, and a job whose task screens were all missed never sets `activeJob` at
 * all. Once any accept has been seen (`ClosedJobReceipt.acceptedSince`), a re-price therefore needs a
 * POSITIVE identity: the same total the closed job's own receipt showed.
 *
 * Platform-agnostic (Principle 8): every input is this region's own records — no `Platform` literal,
 * no wire string. Pure: `obs`-driven, no wall clock, no side effect beyond the emitted log effects.
 */
internal fun PlatformRegionStepper.decideReceiptReprice(
    region: PlatformRegion,
    parsed: ParsedFields.PostTaskFields,
    flow: Flow,
    postTaskTaskId: String?,
    decidedAt: Long,
    captureId: String?,
): PlatformRegion {
    if (flow != Flow.PostTask) return region
    // ONLY an itemized receipt re-prices — an un-itemized re-render carries no new evidence.
    val parsedPay = parsed.parsedPay ?: return region
    // The job whose completions have already been minted, and what its rows currently hold.
    val mark = region.lastClosedJobReceipt ?: return region
    // A live job means the receipt is not a post-close correction at all (and the window-closing hook
    // has already dropped a marker belonging to some OTHER job).
    if (region.activeJob != null) return region

    // Nothing is owed when the rows already hold THIS itemization. The marker is the only thing that
    // can tell that apart from a genuinely late expansion, because the close cleared the receipt out
    // of the region — and since round 4 it also tracks re-prices, so a re-render of a receipt this
    // job was ALREADY re-priced from is a no-op rather than a second event.
    val sameTotal = centsEqual(mark.totalPay, parsed.totalPay)
    if (mark.itemized && sameTotal) return region

    // Ownership. Before any accept, "no job is live" is enough. After one, it proves nothing (the
    // next job may exist with no `activeJob` ever set), so the receipt must carry the SAME total this
    // job's own receipt showed — a positive identity a foreign job's receipt cannot satisfy except by
    // a coincidence of totals, which re-prices with the identical total anyway (the split moves, the
    // money does not). Two deliberate refusals, both fail-null (#745):
    //  - a marker with NO total (its collapsed parse yielded none) has no identity to check;
    //  - a job already re-priced ONCE and since exposed to an accept is terminal — a genuine later
    //    tip update there is indistinguishable from the next job's receipt, and admitting it is how
    //    a corrected $20.00 got dragged back down to a coincidental $16.70.
    if (mark.acceptedSince && !(sameTotal && !mark.repriced && mark.totalPay != null)) return region

    // The denominator, rebuilt as the mint built it — `Task.isAccountableDropoff` (the #498 phantom +
    // #736 unassign firewalls) plus the SAME amdt-#5 [mintQualified] predicate
    // `DeliveryCompletionEffects.mintingDropoffTasks` applies, so Σ shares lands on exactly the rows
    // the mint wrote. The active task is a CANDIDATE: the PostTask-exit mint can complete a task that
    // is STILL ACTIVE under its retire grace (`completedAt` is stamped only when that grace commits),
    // and [mintQualified] admits it only while a `TASK_RETIRE` really is pending — the same evidence
    // the mint required of it.
    val retirePending = region.pendingDestructive?.kind == DestructiveKind.TASK_RETIRE
    val drops = (region.recentTasks + listOfNotNull(region.activeTask))
        .filter { it.jobId == mark.jobId && it.isAccountableDropoff }
        .distinctBy { it.taskId }
        .filter { mintQualified(region, retirePending, it) }
    if (drops.isEmpty()) return region

    // The receipt on screen must belong to THAT job — the announce anchor names the task the receipt
    // was attributed to. A receipt anchored on some other job's drop (or on a pickup) is not evidence
    // about these rows: fail-null.
    val anchor = postTaskTaskId ?: region.lastAnnouncedPostTaskTaskId ?: return region
    if (drops.none { it.taskId == anchor }) return region

    val shares = DropPayApportioner.apportion(parsedPay, drops)
    if (shares.isEmpty()) return region

    return region.copy(
        pendingReceiptReprice = PendingReceiptReprice(
            jobId = mark.jobId,
            parsedPay = parsedPay,
            shares = shares,
            decidedAt = decidedAt,
            sourceCaptureId = captureId,
        ),
        // ATOMIC with the decision: from here the marker describes what the ROWS hold, not what the
        // completion originally carried.
        lastClosedJobReceipt = mark.copy(
            totalPay = parsed.totalPay,
            itemized = true,
            repriced = true,
        ),
    )
}

/** Cent-exact equality for two money figures; a null figure equals nothing. */
private fun centsEqual(a: Double?, b: Double?): Boolean {
    if (a == null || b == null) return false
    return Math.round(a * 100.0) == Math.round(b * 100.0)
}

/**
 * Emit what [decideReceiptReprice] decided — one `DELIVERY_RECEIPT_REPRICE` per delivered drop of the
 * job, idempotent per `(taskId, parsedPay.hashCode())` through the `effects_fired` key.
 *
 * Reads state only: no observation, no re-derivation. The handoff is cleared at the top of the next
 * step, and the `!= p.pendingReceiptReprice` guard makes a value that somehow survived (a snapshot
 * restored mid-flight) emit nothing.
 */
internal fun EffectMap.diffReceiptReprice(
    p: PlatformRegion,
    next: PlatformRegion,
): List<AppEffect> {
    val decided = next.pendingReceiptReprice ?: return emptyList()
    if (decided == p.pendingReceiptReprice) return emptyList()

    val sessionId = next.session?.sessionId ?: p.session?.sessionId
    val payHash = decided.parsedPay.hashCode()
    val effects = decided.shares.map { (taskId, share) ->
        logEffect(
            sessionId,
            AppEventType.DELIVERY_RECEIPT_REPRICE,
            decided.decidedAt,
            DeliveryReceiptRepricePayload(
                jobId = decided.jobId,
                taskId = taskId,
                totalPay = decided.parsedPay.total,
                parsedPay = decided.parsedPay,
                dropRealizedPay = share,
                sourceCaptureId = decided.sourceCaptureId,
            ),
            effectKeyOverride = "log:${AppEventType.DELIVERY_RECEIPT_REPRICE}:$taskId:$payHash",
        )
    }
    // P7: ids + counts + cents only — no store/customer text. One line per decision; the durable
    // dedup makes a repeat frame silent.
    Timber.tag("StateMachine").d(
        "#1033 receipt re-price: job %s, %d drop(s), receipt %d¢",
        decided.jobId, effects.size, Math.round(decided.parsedPay.total * 100.0),
    )
    return effects
}
