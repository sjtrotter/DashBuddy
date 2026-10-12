package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.DropPayApportioner
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.OfferPayFallback
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.isAccountableDropoff
import timber.log.Timber

/**
 * #240 (also folds the #438 B3/#518/#526/#528/#596/#653/#691 history) — the DELIVERY_COMPLETED
 * mint, extracted from [EffectMap] as its own file: it is the single densest concern in the old
 * ~1450-line [EffectMap.kt] (the PostTask-exit mint + the #596 receipt-skip close-out, plus the
 * #528 per-drop pay-apportionment and #691 offer-pay-estimate helpers only this mint uses), so it
 * gets its own file rather than folding into [TaskEffects.kt]'s pickup/dropoff nav lifecycle.
 * `internal`/`private` extensions on [EffectMap] (mirroring the [OfferEffects]/[JobAcceptFlow]
 * precedent) for direct access to [EffectMap.logEffect] and [EffectMap.triggerOverrideEffects]
 * (both widened `private` → `internal` on [EffectMap] for this split). Pure move: no behavior
 * change. Calls [EffectMap.pickupConfirmSweepEffects] (owned by TaskEffects.kt) from the #596
 * close-out to confirm a job that closed without ever reaching a dropoff.
 */
internal fun EffectMap.diffDeliveryCompletion(
    p: PlatformRegion,
    next: PlatformRegion,
    actedPrevFlow: Flow,
    actedNextFlow: Flow,
    obs: Observation,
): List<AppEffect> = buildList {
    // Delivery completed: THIS region's own acted flow leaving PostTask for a non-PostTask
    // flow (#438 item 5 — was the global `prevFlow.flow`/`nextFlow.flow`, which fired this
    // block for every platform whenever ANY platform's frame moved R0 off PostTask). The
    // per-region diff means a non-observing region (p === next → actedPrev == actedNext)
    // never sees the exit, and a foreign platform's frame can't drive this completion. The
    // downstream `completedTask` job-scoping (#518, below) still defends the same-platform
    // cross-JOB leak, which B1 does not obviate.
    // Task ids the PostTask-exit block emits a DELIVERY_COMPLETED for this step — the #596
    // close-out block below must NOT re-emit them (dual-mint exclusivity, amdt #2).
    val emittedThisStep = mutableSetOf<String>()
    val receiptCoverage = p.cachedReceiptCoverage()
    if (actedPrevFlow == Flow.PostTask && actedNextFlow != Flow.PostTask) {
        val taskCompletedOverride = triggerOverrideEffects(obs, TransitionTrigger.TASK_COMPLETED)
        if (taskCompletedOverride != null) {
            addAll(taskCompletedOverride)
        } else {
            // This exit completes the predecessor's task, including an end-A + start-B step.
            val jobSession = closingSession(p, next)
            val sessionId = jobSession?.sessionId
            // #1078 round 4: WHICH task this exit mints is [exitMintCandidate]'s answer, and this
            // block has no eligibility logic of its own left — the stepper records the very same
            // decision into `JobReceiptAnchors.exitMintedTaskIds`, so the durable record can never
            // drift from what was actually emitted.
            val completedTask = exitMintCandidate(p, next, actedPrevFlow, actedNextFlow)
            // COMPLETION and RECEIPT are two questions (#1073 round 15). Round 14 coupled them —
            // this exit refused to complete a drop the cached receipt did not describe — and that
            // could delete a delivered drop's row forever: T1's receipt is cached with coverage
            // {T1}, T2 is delivered, T2's own PostTask frame parses to nothing, and the exit that
            // closes the job refuses T2 while T2 is STILL ACTIVE, so the close-out sweep (which
            // scans `recentTasks`) never sees it either; T2's retire commits afterwards with no job
            // left to sweep. A delivered, arrived drop therefore ALWAYS mints here, exactly as it
            // did before — only its `dropRealizedPay` and the receipt on its payload are
            // coverage-gated below; an uncovered drop can still fold its OFFER_PAY estimate.
            // Coverage never decides WHICH drop completes — that is the subject's job, and its
            // un-arrived-drop residual is #1081.
            val coveredTaskIds = receiptCoverage?.taskIds
            val describedByReceipt =
                completedTask != null && coveredTaskIds?.contains(completedTask.taskId) == true
            if (completedTask != null) {
                val retireSince = p.pendingDestructive
                    ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }?.since
                val finalShape = receiptExitFinalShape(p, completedTask.taskId)
                val dropShare = exitReceiptShare(p, next, completedTask)
                // #630 R2 (load-bearing): a non-final exit must NOT attach the partial receipt to the
                // payload either — else the fold's RECEIPT_TOTAL arm stamps the WHOLE partial total on
                // this row, turning the under-attribution into an OVER-count once siblings mint. The
                // SAME reasoning is why the attach is coverage-gated (#1073 round 14): an uncovered
                // drop carrying the receipt folds at the whole total while the covered drop is priced
                // at it too. With both nulled the row folds PayBasis.NONE — delivered work priced at
                // nothing, which rides the unattributed bucket and the review flag; the close-out
                // sweep's #1073 WARN below is what makes that visible.
                // #1073 round 14 (R1): the ATTACH is coverage-gated too, not just the share. The
                // fold's `RECEIPT_TOTAL` arm prices a drop at the WHOLE `totalPay` whenever a receipt
                // rides its payload, so an UNCOVERED drop that carried the receipt folded at $20 while
                // the covered drop was re-priced at the same $20 — Σ $40 from one $20 receipt. Gated
                // on coverage rather than on `dropShare != null`, because a COLLAPSED receipt has no
                // itemization to split and must still attach its total to the sole drop it covers.
                val receiptForPayload = p.lastPostTaskFields?.takeIf { finalShape && describedByReceipt }
                // #630 R4: the mid-stack partial-receipt seam is observable in the field. PII-safe —
                // counts + jobId only, stable tag (Principle 7; the #691 FIX-6 / #699 D6 precedent).
                // Gate on the SAME pay-bearing predicate the fold/estimate use (parsedPay != null ||
                // totalPay > 0.0) so a pay-bearing COLLAPSED receipt (a $X total with no itemized
                // parsedPay) is not silently skipped by the WARN.
                val payBearingReceipt = p.lastPostTaskFields
                    ?.let { it.parsedPay != null || it.totalPay > 0.0 } == true
                if (!finalShape && payBearingReceipt) {
                    Timber.tag("StateMachine").w(
                        "#630 mid-stack non-final receipt exit: job %s, %d owed dropoffs — " +
                            "dropRealizedPay withheld (partial receipt not split; rides unattributed)",
                        p.activeJob?.jobId,
                        // The union count (#752): job.tasks alone no longer includes unassigned drops.
                        p.activeJob?.let { OfferPayFallback.owedDropoffs(it, p.recentTasks).size } ?: 0,
                    )
                }
                // #1073 round 15: the exit twin of the close-out's R4 line. A delivered drop that
                // mints with NO share and NO receipt — because the cached receipt does not describe
                // it — folds `PayBasis.NONE` unless the #691 estimate rescues it, and nothing else
                // here says so. Ids + counts only (P7); silent on a receipt-less job, which has no
                // coverage at all and takes the estimate below.
                if (coveredTaskIds != null && !describedByReceipt) {
                    Timber.tag("StateMachine").w(
                        "#1073 PostTask exit: job %s, drop %s not described by the cached receipt — " +
                            "no share, no receipt (rides the offer estimate or the review flag)",
                        p.activeJob?.jobId, completedTask.taskId,
                    )
                }
                // #691/#1120: when this drop is uncovered by a receipt, stamp its equal-split
                // share of the accepted-offer pay so it folds a real net row (not $0-unattr).
                // FIX 1: a PostTask-exit mint's job may still be OPEN — stamp only when this is
                // the LAST OPEN owed dropoff (requireFinalShape), so a mid-stack pay-less exit
                // can't over-count (estimate-then-late-receipt / add-on drift / cents drift).
                // #997 amendment B: this mint is INLINE — the job may still be OPEN, so nothing about
                // its final shape is known. It therefore keeps the pre-#996/#997 CONSERVATIVE pooled
                // split over the QUOTED owed orders: shrinking the denominator or attributing
                // per-offer here would let a full-quote stamp be followed by a later activation of a
                // drop that had been filtered out, i.e. Σ stamped > Σ quoted. The whole ladder runs
                // once at the terminal close instead (below).
                val offerResult = p.activeJob?.let { job ->
                    OfferPayFallback.shareFor(
                        job = job,
                        recentTasks = p.recentTasks,
                        mintingTaskId = completedTask.taskId,
                        suppressedByReceipt = completedTask.taskId in receiptCoveredEstimateTaskIds(p, job),
                        requireFinalShape = true,
                    ).also { warnIfUnsplit(job, completedTask.taskId, it) }
                }
                val completedAtStamp = completedTask.completedAt ?: retireSince ?: obs.timestamp
                val payload = deliveryCompletedPayload(
                    task = completedTask,
                    jobId = p.activeJob?.jobId,
                    completedAt = completedAtStamp,
                    postTaskFields = receiptForPayload,
                    sessionEarnings = jobSession?.runningEarnings,
                    dropRealizedPay = dropShare,
                    offerPay = offerResult,
                    jobOfferHashes = p.activeJob?.parentOfferHashes ?: emptyList(),
                )
                // #518: scope idempotency to the completed task, not obs.timestamp, so a
                // re-entered PostTask receipt can't re-fire (and double-count) the same
                // delivery. taskId is replay-stable; the cross-job leak is handled above.
                add(
                    logEffect(
                        sessionId, AppEventType.DELIVERY_COMPLETED, obs.timestamp, payload,
                        effectKeyOverride = "log:${AppEventType.DELIVERY_COMPLETED}:${completedTask.taskId}",
                    )
                )
                emittedThisStep.add(completedTask.taskId)
            }
        }
    }

    // #596 close-out: a physically-complete job closed WITHOUT the post-delivery receipt
    // (the stepper's T1/T2 cleared activeJob) still owes a DELIVERY_COMPLETED for each
    // delivered dropoff — the pre-#596 machine minted that ONLY on a PostTask exit (above),
    // so a receipt-skip (routine on DoorDash: the next offer chains straight over the drop)
    // silently lost the completion AND left the job open to absorb later offers. This fires
    // when the job goes null or is swapped for a new jobId this step. The shared idempotency
    // key ("log:DELIVERY_COMPLETED:<taskId>") makes a double-mint impossible under the live
    // engine's effects_fired dedup — if the receipt already completed the task, this is
    // skipped; if it never rendered, this is the only emission.
    val closedJob = p.activeJob
    if (closedJob != null && next.activeJob?.jobId != closedJob.jobId) {
        // #1078 round 3: through the ONE close-step attribution rule ([closingSession]) — `next` while
        // the session survives (so an in-session close publishes the total THIS observation settled),
        // `prev` when it ends or changes (so a same-step end-A + mint-B books A's delivery to A).
        // Round 2's unconditional prev-first got the first half wrong.
        val jobSession = closingSession(p, next)
        val sessionId = jobSession?.sessionId
        // #526 D5 sweep: a job that closed WITHOUT ever reaching a dropoff (a pickup-only
        // close — no pickup→dropoff edge ever fired to confirm the pickups) still owes
        // PICKUP_CONFIRMED for each arrived pickup. A job that DID reach a dropoff already
        // confirmed its pickups at that edge (all pickups precede all dropoffs), so we skip
        // the sweep there to avoid a redundant per-close re-emission (harmless live under
        // the per-task effects_fired key, but it needn't pollute the stream).
        val jobHadDropoff = (next.recentTasks + listOfNotNull(next.activeTask))
            .any { it.jobId == closedJob.jobId && it.phase == TaskPhase.DROPOFF }
        if (!jobHadDropoff) {
            addAll(
                // #823 F3a: no `ratioJob` — the items:units ratio is DELIBERATELY not learned on this
                // pickup-only close-out (an abnormal completion that never reached a dropoff is not a
                // trustworthy items:units sample). The shop PACE still folds here via RecordShopRate.
                // (Structurally reinforced: `region` is `next`, whose activeJob != closedJob by the
                // guard above, so even the old region-internal lookup could never have learned here.)
                pickupConfirmSweepEffects(
                    sessionId, next, closedJob.jobId, obs,
                    jobOfferHashes = closedJob.parentOfferHashes,
                ),
            )
        }
        // #1078: through the ONE owner — a `SESSION_END` that absorbed a retire carries the same
        // evidence a live `TASK_RETIRE` does, and the #996 amendment-B mask below plus
        // `mintingDropoffTasks` inherit it through this single variable.
        val retirePending = p.retirePendingForMint()
        // #691/#996/#997: the whole offer-pay attribution for this close, computed ONCE (the drops
        // all share one job shape — running the ladder per completion would also let the two mint
        // instants disagree). Suppress only covered drops; uncovered siblings still need the ladder.
        val receiptCoveredTaskIds = receiptCoveredEstimateTaskIds(p, closedJob)
        // #996 amendment B: the consolidation proof, over MINT-QUALIFIED evidence only. `endSession`
        // force-stamps `completedAt` on whatever task was active at a bail (T3), which — for a drop
        // that had already ARRIVED — otherwise satisfies the #749 coverage arm and FORGES a
        // completeness proof for an abandoned job. The amdt-#5 discriminator the mint loop already
        // uses (completed before this step, or retired under a TASK_RETIRE grace) is the SSOT for
        // "this completion is real", so the proof reads the same masked evidence: an unqualified
        // completion is reverted to unfinished, which fails the arm toward absorption. Invariant: an
        // endSession/abandon bail can never yield provenComplete.
        val closedJobProvenComplete = isJobPhysicallyComplete(
            closedJob,
            recentTasks = next.recentTasks.map { t ->
                if (mintQualified(p, retirePending, t)) t else t.copy(completedAt = null)
            },
            justRetired = null,
        )
        val payPlan = OfferPayFallback.closeAttribution(
            job = closedJob,
            recentTasks = p.recentTasks,
            receiptCoveredTaskIds = receiptCoveredTaskIds,
            jobProvenComplete = closedJobProvenComplete,
        )
        // #997 amendment A: every degrade is stated once per close, PII-safe (arm + counts + jobId,
        // no store/customer text — P7) and at DEBUG (a degrade is the designed honest answer, not a
        // defended invariant firing).
        payPlan.degrades.forEach { note ->
            Timber.tag("StateMachine").d(
                "#997 offer-pay attribution degraded to %s: job %s, %d offer(s) over %d drop(s)",
                note.arm, closedJob.jobId, note.offers, note.drops,
            )
        }
        // #997: an accepted, PAY-BEARING offer the ladder could place on no drop at all — its dollars
        // ride the unattributed bucket. ONE WARN per job (a defended invariant: money we accepted has
        // no home). The per-drop eligible-but-unsplit signal below structurally CANNOT report this —
        // a collapsed offer has no minting task — so this is its own edge. Counts + jobId only (P7).
        if (payPlan.unattributedOffers > 0) {
            Timber.tag("StateMachine").w(
                "#997 offer pay unattributed at close: job %s, %d accepted offer(s) matched no drop " +
                    "— those dollars ride the unattributed bucket",
                closedJob.jobId, payPlan.unattributedOffers,
            )
        }
        // #528: split the combined receipt across the job's delivered drops once, so each
        // close-out completion carries its own share (the receipt-skip null rows and the
        // one over-full row become per-drop shares that sum to the receipt total).
        val dropShares = closeReceiptShares(p, next, emittedThisStep)
        for (task in next.recentTasks) {
            if (task.jobId != closedJob.jobId || task.phase != TaskPhase.DROPOFF) continue
            val completedAt = task.completedAt ?: continue
            // #736 belt: a dropoff the dasher UNASSIGNED must never mint a DELIVERY_COMPLETED. Once
            // redundant with the null-completedAt filter above (an INLINE abandon never stamps one),
            // this is now LOAD-BEARING for #752's cross-frame retro-mark, which stamps `unassignedAt`
            // on a grace-retired dropoff that DOES carry a `completedAt` — the marker is the only thing
            // suppressing the fabricated completion for that never-delivered order.
            if (task.unassignedAt != null) continue
            // #498 identity firewall (guardrail): never complete an identity-less phantom.
            if (task.customerNameHash == null && task.customerAddressHash == null) continue
            // amdt #2 exclusivity: the PostTask-exit block already minted this one.
            if (task.taskId in emittedThisStep) continue
            // amdt #5: qualify ONLY (a) a task already completed BEFORE this step, or (b) the
            // active task just retired under retire evidence ([retirePendingForMint] — a live
            // TASK_RETIRE, or since #1078 a SESSION_END that ABSORBED one). This excludes exactly
            // endSession's force-stamp of an active, UNDELIVERED task (T3 false-completion guard):
            // an end with nothing absorbed carries no retire evidence, so neither arm matches. The
            // SAME discriminator masks the completeness proof's evidence above (#996 amendment B)
            // and the #1095 tripwire's.
            if (!mintQualified(p, retirePending, task)) continue
            // #1078 round 5: there is deliberately NO "already minted at a PostTask exit" skip here.
            // Round 3 added one and round 5 withdrew it: `StateManagerV2` snapshots BEFORE the exit's
            // effect is durably written, so a restore can carry an `exitMintedTaskIds` entry for a
            // completion that never reached `app_events` — and the skip would then suppress the very
            // retry master permits, turning a crash into a permanently missing row. A duplicate RAW
            // emission is the lesser evil and is already master's behaviour on this path: the
            // engine's per-task `effects_fired` key (`log:DELIVERY_COMPLETED:<taskId>`) dedups it
            // live, so what actually reaches the log is one row either way. The record survives only
            // as EVIDENCE for the #1095 tripwire, where being wrong can at most silence a WARN.
            // amdt #3: attach the receipt's pay ONLY when the receipt was announced for THIS
            // task (mirror the PostTask path's per-task pinning). A receipt-less completion
            // naturally gets null pay (#528's job), never a normal receipted delivery's pay.
            // #1073 round 14 (R1): AND only when the receipt describes it. The announce id alone was
            // not enough — an uncovered drop that happened to be the announced one folded the whole
            // receipt as `RECEIPT_TOTAL` while a covered sibling was re-priced at the same total.
            val describedByReceipt = receiptCoverage?.taskIds?.contains(task.taskId) == true
            val postTaskFields = p.lastPostTaskFields
                ?.takeIf { p.lastAnnouncedPostTaskTaskId == task.taskId && describedByReceipt }
            // #1120: only receipt-covered drops suppress the offer estimate. Uncovered siblings
            // read their own result from the one close-time attribution plan computed above.
            val offerResult = payPlan.resultFor(task.taskId)
                .also { warnIfUnsplit(closedJob, task.taskId, it) }
            // An uncovered drop with neither a receipt share nor an offer estimate stays unpriced.
            // Keep that visible even though coverage no longer suppresses its estimate (#1120).
            if (receiptCoverage != null && !describedByReceipt &&
                dropShares[task.taskId] == null && offerResult.share == null
            ) {
                Timber.tag("StateMachine").w(
                    "#1073 close-out: job %s, drop %s not described by the cached receipt and the " +
                        "offer estimate is unavailable — folds unpriced (review flag)",
                    closedJob.jobId, task.taskId,
                )
            }
            val payload = deliveryCompletedPayload(
                task = task,
                jobId = closedJob.jobId,
                completedAt = completedAt,
                postTaskFields = postTaskFields,
                // #1078 round 3: read off the SAME decision that named the sessionId above, so an
                // event can never carry one dash's id and another's running total.
                sessionEarnings = jobSession?.runningEarnings,
                dropRealizedPay = dropShares[task.taskId],
                offerPay = offerResult,
                jobOfferHashes = closedJob.parentOfferHashes,
            )
            add(
                logEffect(
                    sessionId, AppEventType.DELIVERY_COMPLETED, obs.timestamp, payload,
                    effectKeyOverride = "log:${AppEventType.DELIVERY_COMPLETED}:${task.taskId}",
                )
            )
            emittedThisStep.add(task.taskId)
        }
    }
}

private fun EffectMap.deliveryCompletedPayload(
    task: Task?,
    jobId: String?,
    completedAt: Long,
    postTaskFields: ParsedFields.PostTaskFields?,
    sessionEarnings: Double?,
    dropRealizedPay: Double? = null,
    /** The whole offer-pay decision — its share AND its resolved provenance ride the payload. */
    offerPay: OfferPayFallback.Result? = null,
    jobOfferHashes: List<String> = emptyList(),
): DeliveryPayload = DeliveryPayload(
    jobId = jobId ?: task?.jobId ?: "unknown",
    taskId = task?.taskId ?: "unknown",
    storeName = task?.storeName,
    customerHash = task?.customerNameHash,
    addressHash = task?.customerAddressHash,
    phaseStartedAt = task?.startedAt ?: completedAt,
    arrivedAt = task?.arrivedAt,
    completedAt = completedAt,
    odometerAtEntry = task?.odometerAtEntry,
    odometerAtArrival = task?.odometerAtArrival,
    deadlineMillis = task?.deadlineMillis,
    totalPay = postTaskFields?.totalPay,
    parsedPay = postTaskFields?.parsedPay,
    dropRealizedPay = dropRealizedPay,
    offerPayShare = offerPay?.share,
    sessionEarningsAtCompletion = sessionEarnings,
    jobOfferHashes = jobOfferHashes,
    // #997: the RESOLVED attribution — the offer this share was actually paid from (null when the
    // ladder pooled) and which rung resolved it. NOT the mint-time slot stamp, which is only a hint
    // (placeholders activate blind first-open). Provenance only — no fold consumer today.
    offerPayAttributedHash = offerPay?.attributedOfferHash,
    offerPayAttribution = offerPay?.arm,
)

/**
 * The #691 FIX-6 observability edge: a drop that was estimate-ELIGIBLE (receipt-less, final shape)
 * yet got NO share — a pay-less quote (job-wide, or this drop's OWN component's) or a minting task
 * outside the eligible owed set (the quoted>delivered halving class). ONE WARN so the silent
 * denominator miss is observable.
 *
 * PII-safe: counts, booleans and jobId only — no store/customer text, stable tag (Principle 7; the
 * #699 D6 join-miss precedent). Every number comes from the SAME [OfferPayFallback.Result] the split
 * produced, so the WARN can never describe a different denominator than the one used, and the
 * denominator is NULLABLE — a mint that never measured (receipt-suppressed, non-final) reports
 * `unmeasured` rather than a fabricated `0 of 0`.
 */
private fun warnIfUnsplit(job: Job, taskId: String, result: OfferPayFallback.Result) {
    if (!result.eligibleButUnsplit) return
    Timber.tag("StateMachine").w(
        "#691 offer-pay estimate eligible but unsplit: job %s, task %s, denominator=%s, " +
            "ownOfferPay=%s, jobOfferTotal=%s — no share stamped; these dollars ride the unattributed bucket",
        job.jobId,
        taskId,
        result.denominator?.let { "${it.eligibleOwed} of ${it.quotedOwed} owed" } ?: "unmeasured",
        // The new pay-less-COMPONENT cause (#997) is otherwise indistinguishable from a pay-bearing
        // denominator miss: the job total can be present while this drop's own offer carried none.
        result.ownOfferPayPresent?.let { if (it) "present" else "null" } ?: "unmeasured",
        if (job.offerPayTotal == null) "null" else "present",
    )
}

/**
 * The amdt-#5 mint qualification, hoisted so the close-out loop AND the #996 completeness proof read
 * ONE definition of "this completion is real" (#997 amendment B): a task already completed BEFORE
 * this step, or the active task just retired under retire evidence ([retirePendingForMint] — a live
 * `TASK_RETIRE`, or since #1078 a `SESSION_END` that absorbed one). It excludes exactly
 * `endSession`'s force-stamp of an active, UNDELIVERED task at a bail — which, for a drop that had
 * already ARRIVED, would otherwise satisfy the #749 coverage arm and FORGE a completeness proof for
 * an abandoned job.
 *
 * `internal` since #1033: the receipt re-price ([diffReceiptReprice]) has to rebuild the SAME
 * denominator the mint used, a step or more later. Re-spelling it there was the #1033 review's R2
 * defect in embryo — a completion minted from the STILL-ACTIVE task (its `completedAt` is stamped
 * only when the retire grace commits) is invisible to a naive `recentTasks + completedAt != null`
 * scan, so the re-price found an empty denominator and silently emitted nothing.
 *
 * **Rounds 6–7 — for a `SESSION_END` pending, arm (b) reads the STEPPER'S ACTUAL DECISION off the
 * task.** A teardown has two outcomes and only one of them is a delivery, but "did it honor?" was
 * being re-derived here from the pending alone (`retirePendingForMint`), which is a second predicate
 * that has to be kept in sync with every refusal the stepper makes — the maturity gate, the
 * `task:unassigned` disown, a lazy-expiry refusal. It fell out of sync at the mode arm's
 * `startingSession` shortcut, which calls `endSession` ahead of the absorbed deadline: the stepper
 * force-stamped, and the sweep minted anyway.
 *
 * So the discriminator is the stepper's own MARK, [Task.honoredRetireAt], written by `endSession`'s
 * honored branch and by nothing else. Round 6 first tried the stamp's VALUE (`completedAt ==
 * absorbedRetireSince`) and that was wrong: a summary sharing the idle frame's timestamp makes
 * `pend.since == absorbedRetireSince`, so the immature teardown's force-stamp (`completedAt =
 * pend.since`) is numerically identical to an honored one and was minted — a timestamp is not
 * provenance. Requiring the mark makes the emitter agree with the stepper BY CONSTRUCTION — a refusal
 * anywhere in the stepper leaves the task unmarked and the mint off, with nothing else to update. It
 * reads [task], which every caller passes from the NEXT region (the post-step copy that carries the
 * mark).
 *
 * A `TASK_RETIRE` pending is unaffected: its arm (b) is the ordinary inline retire, judged as before.
 */
internal fun mintQualified(p: PlatformRegion, retirePending: Boolean, task: Task): Boolean {
    if (p.recentTasks.any { it.taskId == task.taskId && it.completedAt != null }) return true
    if (!retirePending || p.activeTask?.taskId != task.taskId) return false
    val pend = p.pendingDestructive ?: return false
    // Round 7: a teardown's completion counts ONLY when the stepper MARKED it honored. Round 6 read
    // the mark off `completedAt`'s VALUE (== `absorbedRetireSince`), which an equal-timestamped
    // summary — or a clock rollback — let the immature teardown's force-stamp satisfy, fabricating a
    // row the stepper had refused. A timestamp is not provenance; [Task.honoredRetireAt] is.
    if (pend.kind == DestructiveKind.SESSION_END) return task.honoredRetireAt != null
    return true
}

/**
 * The ONE owner of "does this region carry destructive RETIRE evidence for its active task?" — the
 * `retirePending` argument every [mintQualified] caller passes (#1078).
 *
 * Two shapes answer yes, and they are the same fact:
 * - a live `TASK_RETIRE` grace (the pre-#1078 spelling), and
 * - a `SESSION_END` that ABSORBED one. See [PendingDestructive.absorbedRetireSince]: the destructive
 *   slot holds one pending, so an end arming over a retire used to DISCARD the evidence, and the
 *   teardown's force-stamp was then refused by the amdt-#5 T3 guard — $21.00 of fielded delivery
 *   with no row and no warning (2026-09-08, build 8028691a). Only a PROVENANCED retire over an
 *   ARRIVED dropoff is absorbable (`PlatformRegionStepper.absorbableRetire`).
 *
 * The T3 guard itself is untouched: a `SESSION_END` with NO absorbed value still answers false, so a
 * bail on an undelivered task mints nothing, exactly as before.
 */
internal fun PlatformRegion.retirePendingForMint(): Boolean =
    pendingDestructive?.let {
        it.kind == DestructiveKind.TASK_RETIRE ||
            (it.kind == DestructiveKind.SESSION_END && it.absorbedRetireSince != null)
    } == true

/**
 * **The task a PostTask exit would mint a `DELIVERY_COMPLETED` for**, or null (#1078 round 4) — the
 * ONE definition of that eligibility, so the emitter and the stepper's durable record cannot drift.
 *
 * Everything the exit block used to decide inline lives here:
 *
 * - **The edge.** Only THIS region's own acted `PostTask` → non-`PostTask` transition (#438 item 5).
 * - **The subject.** WHICH task an exit completes is the same question as "which drop is this
 *   receipt about" (#1073 round 15), so it has the same owner: [receiptSubjectTaskId], read on the
 *   PRE-step region — the delivered task is still ACTIVE there while its retire grace runs (#431
 *   pt 2), and already in `recentTasks` when it retired on this very step. Job-scoped (#518: a PRIOR
 *   job's stale drop can never be the fallback — the cross-job leak, db seq 117/100), naming the
 *   ACTIVE dropoff first, arrival or not, because the field renders deliveries with no arrival frame.
 * - **#596 amdt 2.** When there is genuinely nothing to complete — job already closed by T1 on a
 *   prior step, no active task, no retire pending — the resolver's unscoped arm must NOT grab a
 *   stale `recentTask` and re-fire a completion the close-out block already minted.
 * - **#564 phase firewall.** A delivery completes a DROPOFF, never a PICKUP. A mid-stack add-on
 *   offer can grace-retire an in-flight PICKUP and a misrecognized summary frame then drives this
 *   exit — fabricating a \$0, customer-less "completion" of a store never delivered (06-21 seq98).
 * - **#653/#498 identity firewall.** An identity-less phantom drop must not mint a full-receipt
 *   completion here either, or it lands the whole receipt on a phantom while its siblings'
 *   apportioned shares already sum to it (the read-model double-count, #653/#630).
 * - **#736 unassigned belt.** The `recentTasks` fallback can select a drop the dasher UNASSIGNED; it
 *   must never be a mint target, or it fabricates a completion for a never-delivered order.
 *
 * [p] is the PRE-step region (every predicate above reads it); [next] only resolves the subject id
 * to the freshest copy of the task, which is why the stepper — which has one region — passes the
 * same region twice and still gets the same TASK ID.
 *
 * **Residual, unchanged and pre-existing (#1081):** a false `post:task` frame while an un-arrived
 * drop is active can still complete that drop, on any exit. Three discriminators were tried across
 * #1075 rounds 14–16 and each was refuted by a concrete sequence.
 *
 * **One caveat the stepper cannot see:** a rule-driven `TASK_COMPLETED` trigger override
 * (`triggerOverrideEffects`) replaces this mint at the emitter. The stepper has no observation-level
 * view of that, so an overridden exit is still recorded as minted — which fails toward NOT
 * double-emitting, the same direction as every other guard here. No checked-in ruleset declares one.
 */
internal fun exitMintCandidate(
    p: PlatformRegion,
    next: PlatformRegion,
    actedPrevFlow: Flow?,
    actedNextFlow: Flow?,
): Task? {
    if (actedPrevFlow != Flow.PostTask || actedNextFlow == Flow.PostTask) return null
    val allowUnscopedFallback =
        !(p.activeJob == null && p.activeTask == null &&
            p.pendingDestructive?.kind != DestructiveKind.TASK_RETIRE)
    val subjectTaskId = p.receiptSubjectTaskId()
        ?.takeIf { p.activeJob != null || allowUnscopedFallback }
        ?: return null
    val task = next.activeTask?.takeIf { it.taskId == subjectTaskId }
        ?: next.recentTasks.lastOrNull { it.taskId == subjectTaskId }
        ?: return null
    if (task.phase != TaskPhase.DROPOFF) return null
    if (task.customerNameHash == null && task.customerAddressHash == null) return null
    if (task.unassignedAt != null) return null
    return task
}

/**
 * Was THIS task's `DELIVERY_COMPLETED` already emitted by a PostTask exit? (#1078)
 *
 * **Evidence for the #1095 tripwire, and NOTHING else — never a mint gate.** Round 3 also used it to
 * skip the close-out sweep's arm (b); round 5 withdrew that, because the failure directions are not
 * symmetric:
 *
 * - As a MINT GATE it fails toward LOSS. `StateManagerV2` snapshots before the exit's effect is
 *   durably written, so a restored region can claim a completion that never reached `app_events`,
 *   and the skip suppresses the retry master would have made — a permanently missing row, which is
 *   the exact class #1078 exists to fix. Emitting twice costs nothing that matters: the engine's
 *   per-task `effects_fired` key dedups it live, and master already double-emits on this path.
 * - As TRIPWIRE EVIDENCE it fails toward QUIET. A wrong "already minted" can only stop a
 *   `JOB_ACCEPT_MISMATCH` WARN from firing; it can never delete a delivery or fabricate one.
 *
 * Why the record exists at all: the exit block mints the drop the receipt was about and leaves it
 * ACTIVE (its `completedAt` is stamped only when the retire commits), so on a later step neither
 * `recentTasks` nor the current pending's provenance can say the mint happened — and the tripwire's
 * masked evidence read a perfectly normal receipted delivery (dash ended before the receipt's retire
 * expired) as a LOST drop and raised a false 1-of-0 alarm.
 *
 * Round 3 DERIVED the answer from `jobReceiptAnchors.exitedPostTask` and `lastAnnouncedPostTaskTaskId`
 * and that was wrong three ways, each a real sequence: in a stacked job D1's exit latches the
 * job-wide flag while D2's receipt moves the announce id, so D2 read as already-minted before it
 * ever exited; an identity-less task whose exit the #498 firewall REFUSED read as minted; and a
 * `ParsedFields.None` PostTask that set no announce id read as not-minted. So the record is per-task
 * and written from the emitter's own eligibility ([exitMintCandidate]) by
 * `PlatformRegionStepper.stampPostTaskExit`, on the same acted-flow edge (`actedFlowEdge`) the
 * emitter diffs. `JobReceiptAnchors` is cleared with its job and by `endSession`/`completeActiveJob`
 * only AFTER the step that reads it; a pre-round-4 snapshot decodes to the empty set.
 *
 * **Two accepted over-reports, both fail-QUIET.** A rule-driven `TASK_COMPLETED` trigger override
 * replaces the mint at the emitter and the stepper cannot see it (no checked-in ruleset declares
 * one). And a PostTask exit straight into `task:unassigned` records the id while the emitter —
 * reading `unassignedAt` on the POST-step task — mints nothing. Both leave the record claiming a
 * mint that did not happen, which at most quiets a WARN.
 */
internal fun PlatformRegion.mintedAtPostTaskExit(taskId: String): Boolean =
    jobReceiptAnchors?.exitMintedTaskIds?.contains(taskId) == true

/**
 * Drops whose estimates a cached pay-bearing receipt suppresses. Coverage gates the share, the
 * receipt attach, AND the estimate: an uncovered sibling remains eligible for OFFER_PAY (#1120).
 * A pseudo-receipt (no itemization and a non-positive total) or missing coverage suppresses nobody.
 * Intersect with this job's records so a stale foreign-job receipt cannot suppress its estimates.
 */
private fun receiptCoveredEstimateTaskIds(region: PlatformRegion, job: Job): Set<String> {
    val fields = region.lastPostTaskFields ?: return emptySet()
    val payBearing = fields.parsedPay != null || fields.totalPay > 0.0
    if (!payBearing) return emptySet()
    val covered = region.cachedReceiptCoverage()?.taskIds.orEmpty()
    return (region.recentTasks + listOfNotNull(region.activeTask))
        .filter { it.jobId == job.jobId && it.taskId in covered }
        .map { it.taskId }
        .toSet()
}

/**
 * #630 R1: the job's *minting-qualified* delivered dropoffs — the EXACT set of completion rows the
 * [DropPayApportioner] splits the receipt across, so the denominator equals the minted-row set and
 * `Σ dropRealizedPay` sums to the receipt total. Replaces the former `jobDropoffTasks`, which
 * returned ALL identity-bearing dropoffs of the job (incl. an endSession-force-stamped or otherwise
 * undelivered drop that entered the denominator but never MINTED → its share evaporated → Σ < total,
 * findings 1/3).
 *
 * Sourced from the region records at the mint step (`recentTasks` + the active task, deduped by id),
 * scoped to [jobId] + the **accountable-dropoff** filter ([Task.isAccountableDropoff]: DROPOFF phase,
 * identity-bearing, non-unassigned — the #498 phantom + #736 unassign firewalls), then filtered to the
 * rows that will actually mint — the SAME amdt#5 qualification the close-out loop applies: already
 * completed before this step, OR the active task just retired under a TASK_RETIRE grace, OR minted by
 * the PostTask-exit block this step ([emittedThisStep]). Both mint blocks call this with identical
 * arguments so a co-firing step agrees on one denominator (and the [DropPayApportioner]'s canonical
 * `taskId` sort makes the remainder cent order-invariant across the two calls, #630 finding 4). The
 * accountable-dropoff filter is the SSOT shared with [OfferPayFallback.isFinalShape] (Principle 5).
 *
 * `internal` and receiver-less since #1073 round 14: `decideReceiptReprice` builds its denominator
 * from THIS function plus one explicit widening arm, so "the same denominator the mint uses" is
 * literally the same expression rather than a second copy that has to be kept in step.
 *
 * **Deliberately NOT routed through [mintQualified]'s honor-mark rule (rounds 6–7).** That rule reads
 * [Task.honoredRetireAt] off a POST-step task copy, and one of this function's two callers —
 * `decideReceiptReprice`, invoked from inside `endSession` — passes the PRE-teardown region for both
 * `p` and `next`, where the drop is still active and not yet marked. Applying the mark rule there
 * would empty the denominator of the very drop the teardown is about to mint and silently withhold
 * the correction (the #1033 R2 defect, exactly). The distinction is what each answers: this is a
 * DENOMINATOR for apportionment, where including a drop that is about to mint is the safe direction,
 * while [mintQualified] gates an EMISSION, where being wrong writes or loses a row.
 */
internal fun mintingDropoffTasks(
    p: PlatformRegion,
    next: PlatformRegion,
    jobId: String?,
    retirePending: Boolean,
    emittedThisStep: Set<String>,
): List<Task> {
    if (jobId == null) return emptyList()
    return (next.recentTasks + listOfNotNull(next.activeTask))
        .filter { it.jobId == jobId && it.isAccountableDropoff }
        .distinctBy { it.taskId }
        .filter { t ->
            val alreadyCompleted =
                p.recentTasks.any { it.taskId == t.taskId && it.completedAt != null }
            val justRetiredUnderGrace = retirePending && p.activeTask?.taskId == t.taskId
            alreadyCompleted || justRetiredUnderGrace || t.taskId in emittedThisStep
        }
}

/** A preserves #1073's whole-job gate; B checks only this receipt's pool. */
internal fun receiptExitFinalShape(region: PlatformRegion, subject: String): Boolean =
    region.activeJob?.let { job ->
        val scope = if (job.acceptedOffers.size <= 1) job.tasks.mapTo(mutableSetOf()) { it.taskId }
            else region.cachedReceiptCoverage()?.taskIds.orEmpty()
        OfferPayFallback.isFinalShape(job, subject, scope)
    } ?: true

/** Pure mint share decisions, shared by the effect payload and the stepper's stamp. */
private fun exitReceiptShare(p: PlatformRegion, next: PlatformRegion, task: Task): Double? {
    if (!receiptExitFinalShape(p, task.taskId)) return null
    return DropPayApportioner.apportion(
        p.lastPostTaskFields?.parsedPay,
        mintingDropoffTasks(p, next, p.activeJob?.jobId,
            retirePending = p.pendingDestructive?.kind == DestructiveKind.TASK_RETIRE,
            emittedThisStep = setOf(task.taskId)).describedBy(p.cachedReceiptCoverage()),
    )[task.taskId]
}

private fun closeReceiptShares(p: PlatformRegion, next: PlatformRegion, emitted: Set<String>) =
    DropPayApportioner.apportion(
        p.lastPostTaskFields?.parsedPay,
        mintingDropoffTasks(p, next, p.activeJob?.jobId,
            retirePending = p.retirePendingForMint(), emittedThisStep = emitted)
            .describedBy(p.cachedReceiptCoverage()),
    )

internal fun mintReceiptShares(
    p: PlatformRegion, next: PlatformRegion, from: Flow, to: Flow,
    exitOverridden: Boolean = false,
): Map<String, Double> = buildMap {
    val exit = if (exitOverridden) null else exitMintCandidate(p, next, from, to)
    if (exit != null) exitReceiptShare(p, next, exit)?.let { put(exit.taskId, it) }
    val job = p.activeJob
    if (job != null && next.activeJob?.jobId != job.jobId) {
        val shares = closeReceiptShares(p, next, setOfNotNull(exit?.taskId))
        next.recentTasks.filter {
            it.jobId == job.jobId && it.isAccountableDropoff && it.completedAt != null &&
                mintQualified(p, p.retirePendingForMint(), it) && it.taskId != exit?.taskId
        }.forEach { task -> shares[task.taskId]?.let { put(task.taskId, it) } }
    }
}
