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
            // WHICH task this exit completes is the SAME question as "which drop is this receipt
            // about" (#1073 round 15), so it has the same one owner: [receiptSubjectTaskId], read on
            // the PRE-step region — the delivered task is still ACTIVE there while its retire grace
            // runs (#431 pt 2), and already in `recentTasks` when it retired on this very step. The
            // resolver is job-scoped (#518: a PRIOR job's stale drop can never be the fallback — the
            // cross-job leak, db seq 117/100) and refuses an UN-ARRIVED drop outright, which is what
            // stops a re-shown receipt from minting the next job's not-yet-reached delivery.
            //
            // #596 amdt 2 survives as its own guard: when there is genuinely nothing being completed
            // on this exit — job already closed by T1 on a prior step, no active task, no retire
            // pending — the resolver's unscoped arm must NOT grab a stale `recentTask` and re-fire a
            // completion the close-out block already minted.
            val allowUnscopedFallback =
                !(p.activeJob == null && p.activeTask == null &&
                    p.pendingDestructive?.kind != DestructiveKind.TASK_RETIRE)
            val subjectTaskId = p.receiptSubjectTaskId()
                ?.takeIf { p.activeJob != null || allowUnscopedFallback }
            val completedTask = subjectTaskId?.let { id ->
                next.activeTask?.takeIf { it.taskId == id }
                    ?: next.recentTasks.lastOrNull { it.taskId == id }
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
            // Fabricating an un-arrived drop is the SUBJECT rule's job, not an arrival test here:
            // the fielded 06-16 session delivers with no arrival frame at all (its dropoff runs
            // nav → pre-arrival → receipt), so an `arrivedAt != null` gate on the mint target
            // detached that delivery from its own receipt.
            //
            // COMPLETION and RECEIPT are two questions (#1073 round 15). Round 14 coupled them —
            // this exit refused to complete a drop the cached receipt did not describe — and that
            // could delete a delivered drop's row forever: T1's receipt is cached with coverage
            // {T1}, T2 is delivered, T2's own PostTask frame parses to nothing, and the exit that
            // closes the job refuses T2 while T2 is STILL ACTIVE, so the close-out sweep (which
            // scans `recentTasks`) never sees it either; T2's retire commits afterwards with no job
            // left to sweep. A delivered, arrived drop therefore ALWAYS mints here, exactly as it
            // did before — only its `dropRealizedPay` and the receipt on its payload are
            // coverage-gated below, so an uncovered drop folds UNPRICED rather than unrecorded.
            // Fabricating an un-arrived drop is prevented by the SUBJECT rule above, never by
            // coverage.
            val coveredTaskIds = p.lastPostTaskCoverage?.taskIds
            val describedByReceipt =
                completedTask != null && coveredTaskIds?.contains(completedTask.taskId) == true
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
                        // #1073 round 13: a receipt prices only the drops it DESCRIBED when it was
                        // read — see [ReceiptCoverage]. Same intersection the re-price applies, so
                        // the mint and a later correction cannot disagree and Σ shares == the
                        // receipt total by construction. An UNCOVERED sibling takes no share (and,
                        // by the amdt-#3 rule below, no receipt on its payload either), so it folds
                        // unpriced and rides the review flag rather than taking money out of an
                        // older receipt — fail-null (#745). Null coverage (a pre-round-13 snapshot)
                        // covers nothing, same direction.
                        dropoffTasks = mintingDropoffTasks(
                            p, next, p.activeJob?.jobId,
                            retirePending = retireSince != null,
                            emittedThisStep = emittedThisStep + completedTask.taskId,
                        ).describedBy(p.lastPostTaskCoverage),
                    )[completedTask.taskId]
                } else {
                    null
                }
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
                // #691: when the whole job was receipt-less, stamp this drop's equal-split
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
                        suppressedByReceipt = receiptSuppressesEstimate(p, job),
                        requireFinalShape = true,
                    ).also { warnIfUnsplit(job, completedTask.taskId, it) }
                }
                val completedAtStamp = completedTask.completedAt ?: retireSince ?: obs.timestamp
                val payload = deliveryCompletedPayload(
                    task = completedTask,
                    jobId = p.activeJob?.jobId,
                    completedAt = completedAtStamp,
                    postTaskFields = receiptForPayload,
                    sessionEarnings = next.session?.runningEarnings ?: p.session?.runningEarnings,
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
        val retirePending = p.pendingDestructive?.kind == DestructiveKind.TASK_RETIRE
        // #691/#996/#997: the whole offer-pay attribution for this close, computed ONCE (the drops
        // all share one job shape — running the ladder per completion would also let the two mint
        // instants disagree). Receipt suppression is evaluated FIRST so a receipted close never pays
        // for the completeness proof or the ladder.
        val estimateSuppressed = receiptSuppressesEstimate(p, closedJob)
        // #996 amendment B: the consolidation proof, over MINT-QUALIFIED evidence only. `endSession`
        // force-stamps `completedAt` on whatever task was active at a bail (T3), which — for a drop
        // that had already ARRIVED — otherwise satisfies the #749 coverage arm and FORGES a
        // completeness proof for an abandoned job. The amdt-#5 discriminator the mint loop already
        // uses (completed before this step, or retired under a TASK_RETIRE grace) is the SSOT for
        // "this completion is real", so the proof reads the same masked evidence: an unqualified
        // completion is reverted to unfinished, which fails the arm toward absorption. Invariant: an
        // endSession/abandon bail can never yield provenComplete.
        val closedJobProvenComplete = !estimateSuppressed && isJobPhysicallyComplete(
            closedJob,
            recentTasks = next.recentTasks.map { t ->
                if (mintQualified(p, retirePending, t)) t else t.copy(completedAt = null)
            },
            justRetired = null,
        )
        val payPlan = OfferPayFallback.closeAttribution(
            job = closedJob,
            recentTasks = p.recentTasks,
            suppressedByReceipt = estimateSuppressed,
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
        val dropShares = DropPayApportioner.apportion(
            parsedPay = p.lastPostTaskFields?.parsedPay,
            // #1073 round 13: intersected with what the CACHED receipt described when it was read
            // (see the PostTask-exit site above). This is the copy that made the round-12 rule
            // wrong — the teardown's re-price scoped its denominator while this one did not, so a
            // 3-drop job's T1 receipt was re-priced whole to T1 here AND split $10 to a
            // receipt-less T2 there: Σ $30 over a $20 receipt.
            dropoffTasks = mintingDropoffTasks(
                p, next, closedJob.jobId,
                retirePending = retirePending,
                emittedThisStep = emittedThisStep,
            ).describedBy(p.lastPostTaskCoverage),
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
            // guard) — that task carries no TASK_RETIRE pending, so neither arm matches. The SAME
            // discriminator masks the completeness proof's evidence above (#996 amendment B).
            if (!mintQualified(p, retirePending, task)) continue
            // amdt #3: attach the receipt's pay ONLY when the receipt was announced for THIS
            // task (mirror the PostTask path's per-task pinning). A receipt-less completion
            // naturally gets null pay (#528's job), never a normal receipted delivery's pay.
            // #1073 round 14 (R1): AND only when the receipt describes it. The announce id alone was
            // not enough — an uncovered drop that happened to be the announced one folded the whole
            // receipt as `RECEIPT_TOTAL` while a covered sibling was re-priced at the same total.
            val describedByReceipt = p.lastPostTaskCoverage?.taskIds?.contains(task.taskId) == true
            val postTaskFields = p.lastPostTaskFields
                ?.takeIf { p.lastAnnouncedPostTaskTaskId == task.taskId && describedByReceipt }
            // #691: eligibility is JOB-scoped on the whole job's receipt state
            // (p.lastPostTaskFields), not the per-task-pinned `postTaskFields` above — a
            // receipt-less close-out (no pay screen at all) stamps every owed drop's offer share; a
            // job that showed any PAY-BEARING receipt stamps none. Indexed out of the ONE plan
            // computed above; the close-out job is already CLOSED → its shape is final.
            val offerResult = payPlan.resultFor(task.taskId)
                .also { warnIfUnsplit(closedJob, task.taskId, it) }
            // #1073 round 14 (R4): a drop this close-out mints with NO share, NO receipt AND a
            // receipt-suppressed offer estimate folds `PayBasis.NONE` — real delivered work priced at
            // nothing. Every other signal is structurally silent here (`warnIfUnsplit` reports the
            // estimate's own arms, and the #630 R4 WARN is exit-only), so this is the one line that
            // says it happened. Ids + counts only (P7); one per drop, and only when all three are
            // true, so a receipt-less job (which folds the estimate) never trips it.
            if (!describedByReceipt && dropShares[task.taskId] == null && estimateSuppressed) {
                Timber.tag("StateMachine").w(
                    "#1073 close-out: job %s, drop %s not described by the cached receipt and the " +
                        "offer estimate is receipt-suppressed — folds unpriced (review flag)",
                    closedJob.jobId, task.taskId,
                )
            }
            val payload = deliveryCompletedPayload(
                task = task,
                jobId = closedJob.jobId,
                completedAt = completedAt,
                postTaskFields = postTaskFields,
                sessionEarnings = next.session?.runningEarnings ?: p.session?.runningEarnings,
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
 * this step, or the active task just retired under a `TASK_RETIRE` grace. It excludes exactly
 * `endSession`'s force-stamp of an active, UNDELIVERED task at a bail — which, for a drop that had
 * already ARRIVED, would otherwise satisfy the #749 coverage arm and FORGE a completeness proof for
 * an abandoned job.
 *
 * `internal` since #1033: the receipt re-price ([diffReceiptReprice]) has to rebuild the SAME
 * denominator the mint used, a step or more later. Re-spelling it there was the #1033 review's R2
 * defect in embryo — a completion minted from the STILL-ACTIVE task (its `completedAt` is stamped
 * only when the retire grace commits) is invisible to a naive `recentTasks + completedAt != null`
 * scan, so the re-price found an empty denominator and silently emitted nothing.
 */
internal fun mintQualified(p: PlatformRegion, retirePending: Boolean, task: Task): Boolean =
    p.recentTasks.any { it.taskId == task.taskId && it.completedAt != null } ||
        (retirePending && p.activeTask?.taskId == task.taskId)

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
    // #1073 round 15: a cached receipt with NO coverage describes nobody, so it is not evidence
    // about this job's drops — and suppressing the estimate on it stranded a perfectly normal
    // delivery unpriced (share and attach are coverage-gated, the estimate was suppressed, the row
    // folded `PayBasis.NONE`). Reachable from a pre-round-13 snapshot, or from any receipt frame
    // whose subject could not be named. The receipt must name at least ONE drop of THIS job.
    val covered = region.lastPostTaskCoverage?.taskIds.orEmpty()
    val jobDrops = (region.recentTasks + listOfNotNull(region.activeTask))
        .filter { it.jobId == job.jobId }
        .map { it.taskId }
        .toSet()
    if (covered.none { it in jobDrops }) return false
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
 *
 * `internal` and receiver-less since #1073 round 14: `decideReceiptReprice` builds its denominator
 * from THIS function plus one explicit widening arm, so "the same denominator the mint uses" is
 * literally the same expression rather than a second copy that has to be kept in step.
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
