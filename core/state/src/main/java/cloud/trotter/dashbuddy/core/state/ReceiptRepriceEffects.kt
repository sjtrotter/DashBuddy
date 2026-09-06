package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.DropPayApportioner
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingReceiptReprice
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.ReceiptCoverage
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.isAccountableDropoff
import timber.log.Timber

/**
 * #1033 review R1 — a marker never outlives its job: the moment ANY other job is active, drop it.
 *
 * This is the cheap structural half of the re-price window. The load-bearing half is temporal and
 * lives in the decision itself (round 6): a re-price is refused once any acceptance has resolved
 * since the closed job's receipt appeared — which covers the case this rule cannot see at all,
 * where the next job is accepted but never minted (every one of its task screens missed), so
 * `activeJob` is never set.
 *
 * `PlatformRegion.lastClosedJobReceipt` names the job whose completions have already been minted.
 * Without this rule the marker outlives its job: `lastAnnouncedPostTaskTaskId` falls back to
 * `recentTasks.lastOrNull()`, so if job B is accepted but every one of its task screens is MISSED,
 * B's receipt would still be anchored on job A's last drop and B's money would be appended as a
 * re-price of A — money attributed to the wrong job, silently. Clearing the marker as soon as ANY
 * new job is active makes that structurally unreachable (the emitter additionally refuses to fire
 * while any job is live — the two are belt and braces, and the emitter's half also covers the
 * frames before the new job's own mint lands).
 *
 * Deliberately keyed on "an active job that is not the marker's own", not on "a job was minted
 * this step": the marker's own job is null-ed out of `activeJob` by `completeActiveJob` at the
 * same moment the marker is stamped, so the two can never be live together — and a same-step
 * close-then-mint (the #596 T2 accept path) correctly clears.
 */
internal fun closeReceiptRepriceWindow(region: PlatformRegion): PlatformRegion {
    val active = region.activeJob ?: return region
    var r = region
    val mark = r.lastClosedJobReceipt
    if (mark != null && active.jobId != mark.jobId) r = r.copy(lastClosedJobReceipt = null)
    // #1033 round 7: the first-occurrence anchors belong to ONE job — a new job starts a new
    // receipt, so they reset with it (and only here, so they survive their own job's close).
    val anchors = r.jobReceiptAnchors
    if (anchors != null && anchors.jobId != active.jobId) r = r.copy(jobReceiptAnchors = null)
    return r
}

/**
 * **The** acted-flow edge this observation drives for this region (#438 item 5 / D3, hoisted to one
 * owner by #1073 round 14) — `prev to next`.
 *
 * The lifecycle edges diff THIS region's own acted flow, not the shared global R0 flow: `diff`
 * iterates every platform, and under concurrency `FlowRegion.flow` is whatever platform last touched
 * the screen. `region.lastActedFlow` is the pre-step value (the [PlatformRegionStepper.step] wrapper
 * stamps the new one after `stepCore`); the fallback to the global prev flow keeps a legacy
 * `lastActedFlow == null` snapshot byte-identical, since a sole region acts on every own frame. A
 * flow-less observation (a timer, a click, a flow-less notification) is NOT an edge — `next` falls
 * back to `prev` — so it can never diff against another platform's `nextFlow.flow`.
 *
 * Three sites derived this by hand before: `updateLifecycle`, `stampPostTaskExit`, and
 * `EffectMap.diffPlatformRegion`. The first two share this; `EffectMap` cannot, because its `next`
 * side reads the POST-step region's own stamped `lastActedFlow` rather than the observation (by then
 * the wrapper has written it), which is the same edge arrived at from the other side of the step.
 */
internal fun actedFlowEdge(
    region: PlatformRegion,
    prevFlow: FlowRegion,
    obs: Observation,
): Pair<Flow, Flow> {
    val prev = region.lastActedFlow ?: prevFlow.flow
    return prev to ((obs as? Observation.FlowObservation)?.flow ?: prev)
}

/**
 * **The** drop a post-delivery receipt is ABOUT (#1073 round 14) — the one resolver the cache, the
 * "Saved: \$X" announce and the PostTask-exit mint all read, so they cannot name three different
 * tasks for one screen.
 *
 * A receipt follows a delivery, so the ladder is evidence-ordered:
 *  1. the ACTIVE task when it is an accountable dropoff that has ARRIVED — the normal shape, where
 *     `completedAt` is not stamped until the retire grace commits, so arrival is the live evidence;
 *  2. else the job's last COMPLETED accountable dropoff (job-scoped while a job is live; the
 *     unscoped fallback is reached only after the close, where the receipt's own job is gone);
 *  3. else the ACTIVE dropoff even un-arrived — but ONLY while no OTHER job's receipt is already on
 *     file (see below); else NULL.
 *
 * The predecessor was `activeTask?.taskId ?: recentTasks.lastOrNull()?.taskId` — unscoped and
 * unfiltered, so a PostTask-classified frame landing while a NEWER, UNDELIVERED drop was active
 * named that drop: the exit fabricated a `DELIVERY_COMPLETED` for it (burning its durable key) and a
 * dash end on that frame re-priced its delivered siblings DOWN to make room for it. A pickup could
 * be named too. Rung 2 closes the same-job version of that: a finished sibling always wins.
 *
 * **Rung 3 is GUARDED, not deleted** (#1073 round 15). Astra's attack is one job over: close job A
 * on its $20 receipt, accept job B, finish B's pickup, start navigating to B's sole customer — then
 * let A's receipt re-show. Rung 2 finds nothing inside B, and an unguarded rung 3 names B's
 * un-ARRIVED drop, so the exit off that frame mints B's completion (consuming
 * `log:DELIVERY_COMPLETED:<B-drop>` before B is reached) with A's money attached. What makes that
 * frame a RE-SHOW is a state fact, not the pixels: the region is already holding a receipt announced
 * for a task of a DIFFERENT job. So rung 3 is refused exactly then — and a receipt whose subject
 * cannot be named describes nobody: nothing is cached, nothing is announced, nothing is minted
 * (fail-null, #745).
 *
 * Deleting rung 3 outright was tried first and REJECTED against the corpus: the fielded 06-16
 * single-delivery session (`ReceiptRepriceReplayTest`) never renders an arrival frame at all — its
 * dropoff runs `dropoff_navigation` → `dropoff_pre_arrival` (a `task:dropoff:navigation` rule) →
 * the receipt — so the delivered drop reaches its own receipt with `arrivedAt == null` and rung 2
 * has nothing yet either. Requiring arrival there detached the receipt from the ONE delivery it
 * described. Arrival is evidence when it exists, never a precondition of being a subject.
 */
internal fun PlatformRegion.receiptSubjectTaskId(): String? {
    val live = activeTask?.takeIf { it.isReceiptSubject }
    if (live?.arrivedAt != null) return live.taskId
    val jobId = activeJob?.jobId
    val lastFinished = recentTasks.lastOrNull {
        it.isReceiptSubject && it.completedAt != null && (jobId == null || it.jobId == jobId)
    }
    if (lastFinished != null) return lastFinished.taskId
    // Rung 3's guard: a cached receipt announced for ANOTHER job's task means the frame on screen is
    // that job's receipt re-showing, and this job's un-arrived drop is not its subject.
    val announced = lastAnnouncedPostTaskTaskId ?: return live?.taskId
    val announcedTask = (recentTasks + listOfNotNull(activeTask)).firstOrNull { it.taskId == announced }
    val foreignReceiptOnFile = announcedTask != null && announcedTask.jobId != jobId
    return if (foreignReceiptOnFile) null else live?.taskId
}

/**
 * Can this task be the subject of a post-delivery receipt at all — a DROPOFF the dasher did not
 * unassign?
 *
 * Deliberately WEAKER than [isAccountableDropoff], which also demands a resolved customer identity
 * (#1073 round 14): identity is a MINT firewall (`#498`), not a question about which drop a screen
 * is showing. A delivery whose customer hashes never resolved still puts its receipt on screen and
 * still earns the "Saved: \$X" bubble, and naming it costs nothing downstream — the mint keeps its
 * own `identityLess` firewall, and `mintingDropoffTasks`' accountable filter excludes it from every
 * denominator regardless.
 */
private val Task.isReceiptSubject: Boolean
    get() = phase == TaskPhase.DROPOFF && unassignedAt == null

/**
 * The drops a receipt read on THIS frame describes (#1073 round 13) — see [ReceiptCoverage].
 *
 * Every accountable dropoff of the subject's job that already carried completion evidence, plus the
 * subject itself — which is in its own receipt by definition, and whose `completedAt` is not stamped
 * until its retire grace commits. Nothing UNQUALIFIED can enter: since round 14 the subject comes
 * from [receiptSubjectTaskId], which is a non-unassigned DROPOFF of this job or null — never a
 * pickup, and never an un-arrived drop while a finished sibling exists.
 * The job is the live one, falling back to the subject's own `jobId` so a post-close re-render still
 * resolves (`completeActiveJob` has already nulled `activeJob` by then).
 *
 * Pure: region records only — no wall clock, no `Platform` literal.
 */
internal fun receiptCoverageAt(region: PlatformRegion, subjectTaskId: String?): ReceiptCoverage {
    val tasks = region.recentTasks + listOfNotNull(region.activeTask)
    val jobId = region.activeJob?.jobId
        ?: tasks.firstOrNull { it.taskId == subjectTaskId }?.jobId
    val covered = tasks
        .filter {
            it.jobId == jobId && it.isAccountableDropoff &&
                (it.taskId == subjectTaskId || it.completedAt != null)
        }
        .map { it.taskId }
        .toSet()
    return ReceiptCoverage(taskIds = covered)
}

/**
 * The ONE writer of the cached-receipt triple (#1073 round 13): the receipt, the drops it describes,
 * and the task it was announced for. Keeping them in one `copy` is what makes "the mint and the
 * re-price read the same denominator" structural rather than a convention.
 */
internal fun cacheReceipt(
    region: PlatformRegion,
    parsed: ParsedFields.PostTaskFields,
    subjectTaskId: String?,
): PlatformRegion = region.copy(
    lastPostTaskFields = parsed,
    lastPostTaskCoverage = receiptCoverageAt(region, subjectTaskId),
    lastAnnouncedPostTaskTaskId = subjectTaskId ?: region.lastAnnouncedPostTaskTaskId,
)

/** The ONE clearer — both `endSession` and `completeActiveJob` drop the receipt AND its coverage. */
internal fun PlatformRegion.clearCachedReceipt(): PlatformRegion =
    copy(lastPostTaskFields = null, lastPostTaskCoverage = null)

/**
 * Intersect a denominator with what the receipt actually described (#1073 round 13). Null coverage
 * describes NOTHING — a pre-round-13 snapshot's cached receipt prices nobody, and the drop falls to
 * the receipt-less path (fail-null, #745) rather than taking a share of a receipt no one can scope.
 */
internal fun List<Task>.describedBy(coverage: ReceiptCoverage?): List<Task> =
    if (coverage == null) emptyList() else filter { it.taskId in coverage.taskIds }

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
 * (from the SAME [DropPayApportioner.apportion] the mint uses, over the SAME denominator — literally
 * the same since round 13: both are `mintingDropoffTasks(…).describedBy(coverage)`), and the fold
 * re-prices the row in place. Frozen economy is never re-costed — net recomputes against the
 * row's OWN `frozenCostPerMile` at the orchestrator.
 *
 * **Two decision points (round 9).** The PostTask arm asks on every itemized receipt FRAME, and the
 * job close asks once more from the receipt it has cached — because `completeActiveJob` CLEARS
 * `lastPostTaskFields`, and a job whose itemized receipt was already on screen when it closed may
 * never render another frame. Without the close-time ask, that row keeps its estimate forever: its
 * first completion was minted un-itemized on an earlier PostTask exit, and the close's re-emission is
 * dropped by the per-taskId `effects_fired` key.
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
    postTaskTaskId: String?,
    /**
     * What this receipt described when it was READ (#1073 round 13) — a fresh [receiptCoverageAt] on
     * the frame path, [PlatformRegion.lastPostTaskCoverage] wherever the receipt is the cached one.
     * The denominator is intersected with it; null describes nothing.
     */
    coverage: ReceiptCoverage?,
    decidedAt: Long,
    captureId: String?,
    /**
     * Admit the region's ACTIVE accountable dropoff into the denominator even though no `TASK_RETIRE`
     * is pending (#1033 review round 10). True only at a terminal-session teardown whose job had
     * already left `PostTask` — the mint ran, so the drop has a row to correct. Everywhere else the
     * amdt-#5 qualification is the whole test.
     */
    mintRanForJob: Boolean = false,
): PlatformRegion {
    // The caller decides WHEN this is asked (#1033 round 9): the PostTask arm asks on a receipt
    // FRAME, and `completeActiveJob` asks once more at the close, from the receipt it is about to
    // drop. Both hand the fields in directly, so no flow test belongs in here.
    // ONLY an itemized receipt re-prices — an un-itemized re-render carries no new evidence.
    val parsedPay = parsed.parsedPay ?: return region
    // The job whose completions have already been minted, and what its rows currently hold.
    val mark = region.lastClosedJobReceipt ?: return region
    // A live job means the receipt is not a post-close correction at all (and the window-closing hook
    // has already dropped a marker belonging to some OTHER job).
    if (region.activeJob != null) return region

    // The ONE thing the stepper suppresses on: its own last decision (#1033 review round 8). A
    // receipt frame re-rendering unchanged must not spend a revision. It deliberately does NOT try to
    // model what the completion ROWS hold — rounds 6–7 did, and both attempts failed toward refusing
    // a legitimate correction (mirroring the mint's exit edge misses its eligibility/final-shape
    // filtering; one task's first completion cannot describe a multi-drop job). The stepper cannot
    // know what persisted; the projector can, and `applyReceiptReprice` no-ops a re-price whose
    // values the row already holds. A redundant event is cheap; a refused correction is not.
    if (parsedPay == mark.lastDecidedPay) return region

    // OWNERSHIP, and this is the whole of it (#1033 review round 6).
    //
    // A receipt carries no job identity. Rounds 2–5 each tried a heuristic — the announce anchor, an
    // accepted-since flag, a same-total match — and every one leaked, because none is evidence about
    // the receipt. What IS knowable is temporal: while no acceptance has resolved since this receipt
    // appeared, no other job can exist to own a receipt, so this frame must be a re-render of this
    // one's. The instant an acceptance resolves that guarantee is gone, and nothing on a later frame
    // brings it back — not a matching total (a total is not identity: a stacked job's $20 receipt
    // redistributed the closed job's drops $5/$15 over a real $10/$10 and installed a foreign
    // tip/base split), not the absence of a live job (a job accepted but never minted never sets
    // `activeJob` at all).
    //
    // Both refusals below are fail-null (#745), and the cost is stated rather than hidden: the
    // STACKED shape — accept the next offer off this receipt, then expand it late — is refused. Layer
    // 1's 8 s collapsed-receipt window is the path that lands that expansion in time; an expansion
    // later than that keeps the #691 `OFFER_PAY` estimate.
    //
    // A second cost is stated rather than defended (#1073 round 14): on a CLOSED multi-drop job the
    // subject is the last completed drop, so a post-close re-render of ONE drop's own receipt (a late
    // tip adjustment) is apportioned across every covered drop. DoorDash fields one combined
    // end-of-job receipt, so the shape is not known to occur; a tip-line-count heuristic was
    // considered and rejected because a stacked order where only one customer tipped has fewer tip
    // lines than drops and would be refused — fail-null on a legitimate correction.
    val receiptSeenAt = mark.receiptSeenAt ?: return region
    val acceptResolvedAt = region.lastAcceptResolvedAt
    if (acceptResolvedAt != null && acceptResolvedAt >= receiptSeenAt) return region

    // The receipt on screen must belong to THIS job — the announce anchor names the task the receipt
    // was attributed to. Resolved BEFORE the denominator, because the teardown widening below is
    // scoped to it (round 11). A receipt anchored on some other job's drop (or on a pickup) is not
    // evidence about these rows: fail-null.
    val anchor = postTaskTaskId ?: region.lastAnnouncedPostTaskTaskId ?: return region

    // The denominator, rebuilt as the mint built it — `Task.isAccountableDropoff` (the #498 phantom +
    // #736 unassign firewalls) plus the SAME amdt-#5 [mintQualified] predicate
    // `DeliveryCompletionEffects.mintingDropoffTasks` applies, so Σ shares lands on exactly the rows
    // the mint wrote.
    //
    // The active task is a CANDIDATE: the PostTask-exit mint can complete a task that is STILL ACTIVE
    // under its retire grace (`completedAt` is stamped only when that grace commits), and
    // [mintQualified] admits it only while a `TASK_RETIRE` really is pending — the same evidence the
    // mint required of it.
    //
    // [mintRanForJob] widens that ONLY at a terminal teardown, and ONLY to the receipt's own anchor
    // task (round 11). Widening to "the active task" alone moved money: at a mid-stack session end —
    // drop 1 delivered and anchoring the cached receipt, drop 2 active and about to be force-completed
    // by the bail — drop 2 joined the denominator and `apportion` split drop 1's $20 receipt $10/$10
    // across both. Fail-WRONG, not fail-null. The anchor is the only task the receipt actually speaks
    // for.
    //
    // And whatever survives that, the receipt only speaks for the drops it DESCRIBED when it was
    // read (#1073 round 13) — the round-11 class one rung down: round 11 scoped the ACTIVE-task
    // exception to the anchor, and a COMPLETED sibling delivered after the receipt appeared was
    // still admitted, so a 3-drop job's T1 receipt was split $10/$10 over T1 and a receipt-less T2.
    // [ReceiptCoverage] is that set, and `DeliveryCompletionEffects` intersects the MINT's
    // `apportion` denominator with the SAME one — which is what makes Σ `dropRealizedPay` == the
    // receipt total hold by construction instead of by two copies of a rule agreeing.
    //
    // The base denominator is [mintingDropoffTasks] ITSELF (#1073 round 14), not a re-spelling of
    // it: `p` and `next` are both this region because a cached-receipt decision has no step to
    // straddle, and `emittedThisStep` is empty because nothing is minting on this one. The ONE thing
    // added is the round-10/11 teardown widening, stated explicitly.
    val retirePending = region.pendingDestructive?.kind == DestructiveKind.TASK_RETIRE
    val widened = region.activeTask?.takeIf {
        mintRanForJob && it.taskId == anchor && it.jobId == mark.jobId && it.isAccountableDropoff
    }
    val drops = (
        mintingDropoffTasks(
            region, region, mark.jobId,
            retirePending = retirePending,
            emittedThisStep = emptySet(),
        ) + listOfNotNull(widened)
        )
        .distinctBy { it.taskId }
        .describedBy(coverage)
    if (drops.isEmpty()) return region
    if (drops.none { it.taskId == anchor }) return region

    val shares = DropPayApportioner.apportion(parsedPay, drops)
    if (shares.isEmpty()) return region

    val revision = mark.repriceRevision + 1
    return region.copy(
        pendingReceiptReprice = PendingReceiptReprice(
            jobId = mark.jobId,
            parsedPay = parsedPay,
            shares = shares,
            decidedAt = decidedAt,
            revision = revision,
            sourceCaptureId = captureId,
        ),
        // ATOMIC with the decision: the revision the events are keyed at (which keeps an X → Y → X
        // itemization sequence's three emissions distinct), and the itemization this decision was
        // made from, so an unchanged re-render does not spend another revision.
        lastClosedJobReceipt = mark.copy(
            repriceRevision = revision,
            lastDecidedPay = parsedPay,
        ),
    )
}

/**
 * Both CACHED-receipt decision points, in one place (#1073 round 13): the job close
 * (`completeActiveJob`, round 9) and the terminal teardown (`endSession`, round 10) ask the same
 * question of the same evidence, and the two hand-copied blocks were one edit apart from drifting.
 *
 * [source] is the pre-close region that still holds the receipt and its [ReceiptCoverage]; [closed]
 * is the region the decision is made against (its `activeJob` already nulled and its
 * `lastClosedJobReceipt` stamped), returned unchanged when nothing is owed. Null coverage decides
 * nothing on its own — `describedBy` empties the denominator — so a pre-round-13 snapshot is
 * fail-null with no extra guard.
 */
internal fun PlatformRegionStepper.decideFromCachedReceipt(
    source: PlatformRegion,
    closed: PlatformRegion,
    decidedAt: Long,
    mintRanForJob: Boolean = false,
): PlatformRegion {
    val cached = source.lastPostTaskFields ?: return closed
    return decideReceiptReprice(
        region = closed,
        parsed = cached,
        postTaskTaskId = null,
        coverage = source.lastPostTaskCoverage,
        decidedAt = decidedAt,
        captureId = null,
        mintRanForJob = mintRanForJob,
    )
}

/**
 * Emit what [decideReceiptReprice] decided — one `DELIVERY_RECEIPT_REPRICE` per delivered drop of the
 * job, keyed `…:<taskId>:<jobId>:r<revision>` in `effects_fired` (a monotonic DECISION revision, not
 * the receipt's content: an X→Y→X itemization hashed back onto its own first key and the third
 * emission was dropped).
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
            // Keyed by DECISION REVISION, not by the receipt's content (#1033 review round 6): an
            // X → Y → X itemization sequence hashes back to the FIRST key, and the durable
            // `effects_fired` idempotency then drops the third emission — leaving the row at Y while
            // the marker says X. A monotonic revision cannot repeat. Identical consecutive receipts
            // never get here at all; the already-priced check suppresses them earlier and cheaper.
            effectKeyOverride =
                "log:${AppEventType.DELIVERY_RECEIPT_REPRICE}:$taskId:${decided.jobId}:r${decided.revision}",
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
