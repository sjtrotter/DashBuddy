package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.DropPayApportioner
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingReceiptReprice
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.ReceiptCoverage
import cloud.trotter.dashbuddy.domain.state.Task
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
 * The drops a receipt read on THIS frame describes (#1073 round 13) — see [ReceiptCoverage].
 *
 * The announced task is always in its own receipt (it is the delivery the receipt closed), plus
 * every accountable dropoff of that task's job that already carried completion evidence. The job is
 * the live one, falling back to the announced task's own `jobId` so a post-close re-render still
 * resolves (`completeActiveJob` has already nulled `activeJob` by then).
 *
 * Pure: region records + the frame's own timestamp, no wall clock, no `Platform` literal.
 */
internal fun receiptCoverageAt(
    region: PlatformRegion,
    postTaskTaskId: String?,
    readAt: Long,
): ReceiptCoverage {
    val tasks = region.recentTasks + listOfNotNull(region.activeTask)
    val jobId = region.activeJob?.jobId
        ?: tasks.firstOrNull { it.taskId == postTaskTaskId }?.jobId
    val covered = tasks
        .filter {
            it.jobId == jobId && it.isAccountableDropoff &&
                (it.taskId == postTaskTaskId || (it.completedAt != null && it.unassignedAt == null))
        }
        .map { it.taskId }
        .toMutableSet()
    if (postTaskTaskId != null) covered += postTaskTaskId
    return ReceiptCoverage(readAt = readAt, taskIds = covered)
}

/**
 * The ONE writer of the cached-receipt triple (#1073 round 13): the receipt, the drops it describes,
 * and the task it was announced for. Keeping them in one `copy` is what makes "the mint and the
 * re-price read the same denominator" structural rather than a convention.
 */
internal fun cacheReceipt(
    region: PlatformRegion,
    parsed: ParsedFields.PostTaskFields,
    postTaskTaskId: String?,
    readAt: Long,
): PlatformRegion = region.copy(
    lastPostTaskFields = parsed,
    lastPostTaskCoverage = receiptCoverageAt(region, postTaskTaskId, readAt),
    lastAnnouncedPostTaskTaskId = postTaskTaskId ?: region.lastAnnouncedPostTaskTaskId,
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
    val retirePending = region.pendingDestructive?.kind == DestructiveKind.TASK_RETIRE
    val drops = (region.recentTasks + listOfNotNull(region.activeTask))
        .filter { it.jobId == mark.jobId && it.isAccountableDropoff }
        .distinctBy { it.taskId }
        .filter {
            mintQualified(region, retirePending, it) ||
                (mintRanForJob && it.taskId == region.activeTask?.taskId && it.taskId == anchor)
        }
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
    if (cached.parsedPay == null) return closed
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
