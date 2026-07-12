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
    if (actedPrevFlow == Flow.PostTask && actedNextFlow != Flow.PostTask) {
        val taskCompletedOverride = triggerOverrideEffects(obs, TransitionTrigger.TASK_COMPLETED)
        if (taskCompletedOverride != null) {
            addAll(taskCompletedOverride)
        } else {
            val sessionId = next.session?.sessionId ?: p.session?.sessionId
            // The delivered task may still be ACTIVE on PostTask exit —
            // its retire grace commits up to ~2.5s later (#431 pt 2).
            // Prefer it (same-id guard: a stacked next-task frame mints
            // a NEW activeTask on this same exit, which must not be the
            // one we report); fall back to the last committed task.
            // #518: resolve the task being completed — the still-active delivered task, or
            // the one just retired into recentTasks on this exit, JOB-SCOPED so a PRIOR job's
            // stale task can never be the fallback (the cross-job leak, db seq 117/100 — a
            // prior job's already-completed dropoff re-firing under the new job). When there
            // is no active job to scope by, keep the prior unscoped behaviour (the payload's
            // jobId would be null regardless).
            val completedJobId = p.activeJob?.jobId
            // #596 amdt 2: when there's genuinely nothing being completed on this exit —
            // job already closed (by T1 on a prior step), no active task, no retire pending —
            // the unscoped (job-less) fallback must NOT grab a stale recentTask and re-fire a
            // completion the close-out block already minted. The scoped arm (job present) is
            // unaffected.
            val allowUnscopedFallback =
                !(p.activeJob == null && p.activeTask == null &&
                    p.pendingDestructive?.kind != DestructiveKind.TASK_RETIRE)
            val completedTask = next.activeTask?.takeIf { it.taskId == p.activeTask?.taskId }
                ?: next.recentTasks.lastOrNull {
                    if (completedJobId == null) allowUnscopedFallback else it.jobId == completedJobId
                }
            // #564: a delivery completes a DROPOFF, never a PICKUP. A mid-stack add-on offer
            // can grace-retire an in-flight PICKUP task and a transient/misrecognized
            // delivery-summary frame then drives this PostTask-exit — fabricating a $0,
            // customer-less "completion" of a store that was never delivered (06-21 seq98:
            // Smoky Mo's pickup …32 completed at the moment the Burger King add-on was
            // accepted). Only a task that actually reached the dropoff phase may complete.
            // #653 firewall parity: mirror the #596 close-out path's #498 identity firewall
            // (below, `customerNameHash == null && customerAddressHash == null`) here too —
            // an identity-less phantom drop must not mint a full-receipt completion from the
            // PostTask-exit path either, or it would land the whole receipt on a phantom while
            // its siblings' apportioned shares already sum to it (the read-model double-count,
            // #653/#630). An identity-BEARING single drop is the normal path, unaffected.
            val identityLess = completedTask != null &&
                completedTask.customerNameHash == null &&
                completedTask.customerAddressHash == null
            // #736 belt: the `recentTasks.lastOrNull { jobId }` fallback above can select a drop the
            // dasher UNASSIGNED (a null-completedAt abandon that the close-out sweep already filters) —
            // it must never be the PostTask-exit mint target either, or it fabricates a
            // DELIVERY_COMPLETED for a never-delivered order. Mirrors the close-out's `unassignedAt`
            // firewall (below) at this second mint site.
            val unassigned = completedTask?.unassignedAt != null
            if (completedTask != null && completedTask.phase == TaskPhase.DROPOFF &&
                !identityLess && !unassigned
            ) {
                val retireSince = p.pendingDestructive
                    ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }?.since
                // #630 R2: gate the receipt split on the job's FINAL shape (the SAME predicate
                // #691's requireFinalShape uses — [OfferPayFallback.isFinalShape], one definition).
                // At a mid-stack, non-final exit the job's `lastPostTaskFields` is only a PARTIAL
                // receipt (covers the delivered-so-far drops) over a PROVISIONAL denominator, so
                // splitting it would freeze a wrong share on this drop while later siblings mint
                // against the FINAL receipt → Σ drifts below the real total (findings 1/3). A
                // no-activeJob exit is trivially final (its jobId would be null anyway).
                val finalShape =
                    p.activeJob?.let { OfferPayFallback.isFinalShape(it, completedTask.taskId) } ?: true
                // #528/#630 R1: attribute this drop's share of the combined receipt over the
                // minting-qualified denominator (the exact rows that will ever mint), so the shares
                // sum to the receipt total. Withhold entirely on a non-final exit (R2/R4).
                val dropShare = if (finalShape) {
                    DropPayApportioner.apportion(
                        parsedPay = p.lastPostTaskFields?.parsedPay,
                        dropoffTasks = mintingDropoffTasks(
                            p, next, p.activeJob?.jobId,
                            retirePending = retireSince != null,
                            emittedThisStep = emittedThisStep + completedTask.taskId,
                        ),
                    )[completedTask.taskId]
                } else {
                    null
                }
                // #630 R2 (load-bearing): a non-final exit must NOT attach the partial receipt to the
                // payload either — else the fold's RECEIPT_TOTAL arm stamps the WHOLE partial total on
                // this row, turning the under-attribution into an OVER-count once siblings mint. With
                // both nulled the row folds PayBasis.NONE and its dollars ride the unattributed bucket.
                val receiptForPayload = if (finalShape) p.lastPostTaskFields else null
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
                // #691: when the whole job was receipt-less, stamp this drop's equal-split
                // share of the accepted-offer pay so it folds a real net row (not $0-unattr).
                // FIX 1: a PostTask-exit mint's job may still be OPEN — stamp only when this is
                // the LAST OPEN owed dropoff (requireFinalShape), so a mid-stack pay-less exit
                // can't over-count (estimate-then-late-receipt / add-on drift / cents drift).
                val offerShare = offerPayShareFor(
                    region = p,
                    job = p.activeJob,
                    taskId = completedTask.taskId,
                    requireFinalShape = true,
                )
                val payload = deliveryCompletedPayload(
                    task = completedTask,
                    jobId = p.activeJob?.jobId,
                    completedAt = completedTask.completedAt ?: retireSince ?: obs.timestamp,
                    postTaskFields = receiptForPayload,
                    sessionEarnings = next.session?.runningEarnings ?: p.session?.runningEarnings,
                    dropRealizedPay = dropShare,
                    offerPayShare = offerShare,
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
        val sessionId = next.session?.sessionId ?: p.session?.sessionId
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
                pickupConfirmSweepEffects(
                    sessionId, next, closedJob.jobId, obs,
                    jobOfferHashes = closedJob.parentOfferHashes,
                ),
            )
        }
        val retirePending = p.pendingDestructive?.kind == DestructiveKind.TASK_RETIRE
        // #528: split the combined receipt across the job's delivered drops once, so each
        // close-out completion carries its own share (the receipt-skip null rows and the
        // one over-full row become per-drop shares that sum to the receipt total).
        val dropShares = DropPayApportioner.apportion(
            parsedPay = p.lastPostTaskFields?.parsedPay,
            dropoffTasks = mintingDropoffTasks(
                p, next, closedJob.jobId,
                retirePending = retirePending,
                emittedThisStep = emittedThisStep,
            ),
        )
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
            // active task just retired under a TASK_RETIRE grace. This excludes exactly
            // endSession's force-stamp of an active, UNDELIVERED task (T3 false-completion
            // guard) — that task carries no TASK_RETIRE pending, so neither arm matches.
            val alreadyCompleted =
                p.recentTasks.any { it.taskId == task.taskId && it.completedAt != null }
            val justRetiredUnderGrace = retirePending && p.activeTask?.taskId == task.taskId
            if (!alreadyCompleted && !justRetiredUnderGrace) continue
            // amdt #3: attach the receipt's pay ONLY when the receipt was announced for THIS
            // task (mirror the PostTask path's per-task pinning). A receipt-less completion
            // naturally gets null pay (#528's job), never a normal receipted delivery's pay.
            val postTaskFields = p.lastPostTaskFields
                ?.takeIf { p.lastAnnouncedPostTaskTaskId == task.taskId }
            // #691: eligibility is JOB-scoped on the whole job's receipt state
            // (p.lastPostTaskFields), not the per-task-pinned `postTaskFields` above — a
            // receipt-less close-out (no pay screen at all) stamps every owed drop's
            // equal-split offer share; a job that showed any PAY-BEARING receipt stamps none.
            // The close-out job is already CLOSED → its shape is final (requireFinalShape=false).
            val offerShare = offerPayShareFor(
                region = p,
                job = closedJob,
                taskId = task.taskId,
                requireFinalShape = false,
            )
            val payload = deliveryCompletedPayload(
                task = task,
                jobId = closedJob.jobId,
                completedAt = completedAt,
                postTaskFields = postTaskFields,
                sessionEarnings = next.session?.runningEarnings ?: p.session?.runningEarnings,
                dropRealizedPay = dropShares[task.taskId],
                offerPayShare = offerShare,
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
    offerPayShare: Double? = null,
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
    offerPayShare = offerPayShare,
    sessionEarningsAtCompletion = sessionEarnings,
    jobOfferHashes = jobOfferHashes,
)

/**
 * #691 offer-pay estimate share for [taskId] of [job] when the job was WHOLLY receipt-less — the
 * write-side stamp that lets a receipt-less shop delivery fold a real net row instead of a
 * $0-unattributed one. Thin edge over the pure [OfferPayFallback] policy: this computes the two
 * inputs only the region can see — the job-scoped receipt-evidence verdict
 * ([receiptSuppressesEstimate]) and the per-site final-shape flag — then delegates the eligibility
 * + split, and owns the observability WARN (FIX 6) for an eligible-but-unsplit drop.
 *
 * [requireFinalShape] is true at the PostTask-exit mint (job may still be open — stamp only the
 * LAST OPEN owed drop) and false at the #596 close-out (job already closed → final shape).
 */
private fun EffectMap.offerPayShareFor(
    region: PlatformRegion,
    job: Job?,
    taskId: String,
    requireFinalShape: Boolean,
): Double? {
    if (job == null) return null
    val result = OfferPayFallback.shareFor(
        job = job,
        recentTasks = region.recentTasks,
        mintingTaskId = taskId,
        suppressedByReceipt = receiptSuppressesEstimate(region, job),
        requireFinalShape = requireFinalShape,
    )
    if (result.eligibleButUnsplit) {
        // The drop is estimate-ELIGIBLE (receipt-less, final shape) yet got NO share — a pay-less
        // offer or a minting task outside the owed set (the quoted>delivered halving class). WARN
        // it so the silent-denominator miss is observable. PII-safe: counts + jobId only, no
        // store/customer text, stable tag (Principle 7; the #699 D6 join-miss precedent). The count
        // is the SAME union set the split uses ([OfferPayFallback.owedDropoffs] — job.tasks no
        // longer retains unassigned drops post-#752), so the WARN describes the real denominator.
        Timber.tag("StateMachine").w(
            "#691 offer-pay estimate eligible but unsplit: job %s, %d owed dropoffs, offerTotal=%s " +
                "— no share stamped; these dollars ride the unattributed bucket",
            job.jobId,
            OfferPayFallback.owedDropoffs(job, region.recentTasks).size,
            if (job.offerPayTotal == null) "null" else "present",
        )
    }
    return result.share
}

/**
 * #691 receipt-evidence verdict: does [job] show a PAY-BEARING post-task receipt attributable to
 * ITSELF — in which case the offer-pay estimate is withheld (a real receipt is truth)?
 *
 * Two guards, both learned from the adversarial review:
 * - **Pay-bearing (FIX 2a):** `ParsedFieldsFactory.buildPostTask` coerces a missing total to
 *   `0.0`, so a transient `delivery_summary_collapsed` frame that fails to parse produces a $0.00
 *   `PostTaskFields` — a pseudo-receipt. A real $0 delivery receipt isn't a thing, so a $0 total
 *   with no itemized `parsedPay` is NOT evidence and must NOT suppress the estimate. Same
 *   predicate the fold uses (`RecordFolds.payBearingReceipt`) — one definition, two sites.
 * - **Job-scoped (FIX 2b):** `lastPostTaskFields`/`lastAnnouncedPostTaskTaskId` are REGION-scoped
 *   and survive `completeActiveJob` clearing `lastPostTaskFields` (the announce id is NOT cleared),
 *   so a flickered PREVIOUS-job receipt can re-set them. Suppress only when the receipt is
 *   attributable to THIS job: the announce id is in this job's tasks, OR is null (conservative —
 *   fail-closed against over-count), OR is not PROVABLY another job's task. Do NOT suppress only
 *   when the announce id provably belongs to a DIFFERENT job's task (the stale cross-job flicker).
 *
 * Pure: derives only from region records; no wall clock.
 */
private fun EffectMap.receiptSuppressesEstimate(region: PlatformRegion, job: Job): Boolean {
    val fields = region.lastPostTaskFields ?: return false
    val payBearing = fields.parsedPay != null || fields.totalPay > 0.0
    if (!payBearing) return false
    val announceId = region.lastAnnouncedPostTaskTaskId ?: return true // null → conservative suppress
    if (job.tasks.any { it.taskId == announceId }) return true // this job's receipt → suppress
    val regionTasks = region.recentTasks + listOfNotNull(region.activeTask)
    val provablyAnotherJob = regionTasks.any { it.taskId == announceId && it.jobId != job.jobId }
    return !provablyAnotherJob // unknown id → conservative suppress; foreign id → do not suppress
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
 */
private fun EffectMap.mintingDropoffTasks(
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
