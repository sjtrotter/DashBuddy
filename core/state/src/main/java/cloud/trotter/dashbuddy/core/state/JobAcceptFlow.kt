package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.StoreNameMatch
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import java.util.Locale

/**
 * #526 / #237 — the **accept → job-mint → placeholder → swap** unit, extracted from
 * [PlatformRegionStepper] (which had grown past the ~1200-line ceiling the #237 family guards). Pure
 * move: the accepted-offer consumption ([consumeAcceptIntoJob]), the fresh-mint / add-on append, the
 * pre-created dropoff + pickup placeholders, and the D4a mis-bind swap guard — all as `internal`
 * extension functions on [PlatformRegionStepper] so they still share its deterministic
 * [PlatformRegionStepper.mintId] / [PlatformRegionStepper.completeActiveJob] helpers and the top-level
 * [isJobPhysicallyComplete]. (#438 B3 retired the accept-stash arming/consumption — the accepted
 * offer now lives on the region's own `pendingOffers`, so [OfferLifecycle] owns its lifecycle.)
 */
/**
 * #503 slice 3b: pre-create one customer-TBD dropoff placeholder per order an accepted offer
 * covers, so a Job owns ordered dropoffs for a stack (not just a single drop). Each dropoff
 * screen later RESOLVES its customer onto the next open placeholder by name (slice 3 + 3b-2).
 * The count comes from the offer's parsed order list (fallback 1 when the offer wasn't parsed);
 * ids are minted at [startOffset]..[startOffset]+count-1 off the region's mint counter, which the
 * caller advances past in the same copy().
 */
internal fun PlatformRegionStepper.preCreatedDropoffs(
    region: PlatformRegion,
    obs: Observation,
    jobId: String,
    count: Int,
    startOffset: Long,
): List<Task> = (0 until count).map { i ->
    Task(
        taskId = mintId("task", region, obs, offset = startOffset + i),
        jobId = jobId,
        phase = TaskPhase.DROPOFF,
        customerNameHash = null,
        startedAt = obs.timestamp,
    )
}

/**
 * #438 B3 (was #526 D1): the accepted-offer inputs a job mint needs, sourced from the region's own
 * accepted-pending-consumption [PendingOffer] via [acceptInputsFromPending]. One shape so
 * [consumeAcceptIntoJob] is source-agnostic — it serves the accept-adjacent happy path AND the F3
 * teardown-race recovery (both now read the same owned survivor, so the old stash variant is gone).
 */
internal data class AcceptInputs(
    val offerHash: String?,
    val economics: AcceptedOfferEconomics,
    /** Raw per-order store hints (`orders.map { storeName }`); empty when the offer wasn't parsed. */
    val storeHints: List<String>,
    /** One dropoff placeholder per order; fallback 1 when the offer wasn't parsed. */
    val dropoffCount: Int,
)

internal fun PlatformRegionStepper.acceptInputsFromPending(pending: PendingOffer?, acceptedAt: Long?): AcceptInputs {
    val parsedOffer = pending?.offerFields?.parsedOffer
    val eval = pending?.evaluation
    val storeHints = parsedOffer?.orders?.map { it.storeName } ?: emptyList()
    return AcceptInputs(
        offerHash = pending?.offerHash,
        economics = AcceptedOfferEconomics(
            offerHash = pending?.offerHash,
            payAmount = eval?.payAmount ?: parsedOffer?.payAmount,
            netPay = eval?.netPayAmount,
            estMinutes = eval?.estimatedTimeMinutes ?: parsedOffer?.timeToCompleteMinutes?.toDouble(),
            distanceMiles = eval?.distanceMiles ?: parsedOffer?.distanceMiles,
            // The honest accept moment (the accept-click time); falls back to the consume frame.
            acceptedAt = acceptedAt ?: pending?.acceptClickAt ?: pending?.presentedAt ?: 0L,
        ),
        storeHints = storeHints,
        dropoffCount = storeHints.size.takeIf { it > 0 } ?: 1,
    )
}

/**
 * #438 B3 (was #526 D1): consume an accepted offer into the job graph — fresh mint, #596 T2
 * close+mint on an independent offer over a finished job, or add-on append. The caller has already
 * removed the consumed offer from [PlatformRegion.pendingOffers]; this only assembles the job graph.
 * The single entry point shared by the accept-adjacent, add-on, and fallback branches.
 */
internal fun PlatformRegionStepper.consumeAcceptIntoJob(
    region: PlatformRegion,
    obs: Observation.FlowObservation,
    inputs: AcceptInputs,
): PlatformRegion {
    val existing = region.activeJob ?: return mintFreshJobFromAccept(region, obs, inputs)

    // #596 T2: an accept while the active job is already physically complete is an INDEPENDENT
    // offer, not an add-on — do NOT fold it into a never-closed job (the 06-30 job-61 case: one
    // never-closed job swallowed three offers, $19 of $45.75 unattributed). Close the old job and
    // mint a fresh one. At accept time the final drop may still be inside its TASK_RETIRE grace
    // (activeTask not yet retired); commit that retire inline first (honest completion = pend.since,
    // matching retireActiveTask) so the completeness check sees the finished drop. Skipped when the
    // retire was armed by deliberating on THIS add-on offer (armedFromFlow == OfferPresented).
    run {
        val pend = region.pendingDestructive?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }
        if (pend?.armedFromFlow == Flow.OfferPresented) return@run
        val justRetired = if (pend != null) region.activeTask?.copy(completedAt = pend.since) else null
        val recentForCheck =
            if (justRetired != null) region.recentTasks + justRetired else region.recentTasks
        if (isJobPhysicallyComplete(existing, recentForCheck, justRetired)) {
            val committed = if (justRetired != null) {
                region.copy(
                    activeTask = null,
                    recentTasks = recentForCheck.takeLast(PlatformRegionStepper.MAX_RECENT_TASKS),
                    pendingDestructive = null,
                )
            } else region
            return mintFreshJobFromAccept(completeActiveJob(committed), obs, inputs)
        }
    }

    return appendAddOn(region, existing, obs, inputs)
}

/**
 * Mint a fresh Job from an accepted offer: jobId at offset 0, the D customer-TBD dropoff
 * placeholders at offsets 1..D, then (#526 D2) one PICKUP placeholder per distinct store at
 * offsets D+1..D+P — all in one copy() that advances the mint counter past the whole batch (#344).
 */
internal fun PlatformRegionStepper.mintFreshJobFromAccept(
    base: PlatformRegion,
    obs: Observation.FlowObservation,
    inputs: AcceptInputs,
): PlatformRegion {
    val jobId = mintId("job", base, obs)
    val dropoffs = preCreatedDropoffs(base, obs, jobId, inputs.dropoffCount, startOffset = 1)
    // #526 D2: one PICKUP placeholder per distinct store, minted AFTER the dropoffs so existing
    // dropoff ids (and the slice-3/3b tests) are unchanged — offsets job=0, dropoffs=1..D,
    // pickups=D+1..D+P.
    val pickupHints = distinctStoreHints(inputs.storeHints)
    val pickups = preCreatedPickups(base, obs, jobId, pickupHints, startOffset = (1 + inputs.dropoffCount).toLong())
    return base.copy(
        activeJob = Job(
            jobId = jobId,
            offerStoreHint = inputs.storeHints,
            parentOfferHash = inputs.offerHash,
            acceptedOffers = listOf(inputs.economics),
            startedAt = obs.timestamp,
            tasks = dropoffs + pickups,
        ),
        // job(1) + D dropoffs + P pickups, all minted in this one copy() (#344).
        mintCounter = base.mintCounter + 1 + inputs.dropoffCount + pickups.size,
    )
}

/**
 * Append an add-on accept into the active job: its own D dropoff placeholders (offsets 0..D-1),
 * plus (#526 D2) pickup placeholders only for stores the job does NOT already own (a same-store
 * add-on folds into the existing pickup, #499), at offsets D..D+P-1. No job mint this step.
 * Deduped by offerHash so a re-entered OfferPresented for the same offer can't double-count.
 */
internal fun PlatformRegionStepper.appendAddOn(
    region: PlatformRegion,
    existing: Job,
    obs: Observation.FlowObservation,
    inputs: AcceptInputs,
): PlatformRegion {
    val alreadyCounted = inputs.offerHash != null &&
        existing.acceptedOffers.any { it.offerHash == inputs.offerHash }
    if (alreadyCounted) return region
    val addOnDropoffs = preCreatedDropoffs(region, obs, existing.jobId, inputs.dropoffCount, startOffset = 0)
    // #526 FIX3c: dedup incoming add-on stores against BOTH the existing pickups' offer hints
    // AND their resolved store names — a bare-fallback job (hint null, storeName "H-E-B") would
    // otherwise get a duplicate H-E-B placeholder from a same-store add-on.
    val existingPickupHints = existing.tasks
        .filter { it.phase == TaskPhase.PICKUP }
        .flatMap { listOfNotNull(it.expectedStoreHint, it.storeName) }
        .map { it.lowercase(Locale.ROOT) }
        .toSet()
    val addOnPickupHints = distinctStoreHints(inputs.storeHints)
        .filter { it.lowercase(Locale.ROOT) !in existingPickupHints }
    val addOnPickups = preCreatedPickups(region, obs, existing.jobId, addOnPickupHints, startOffset = inputs.dropoffCount.toLong())
    return region.copy(
        activeJob = existing.copy(
            acceptedOffers = existing.acceptedOffers + inputs.economics,
            offerStoreHint = existing.offerStoreHint + inputs.storeHints,
            tasks = existing.tasks + addOnDropoffs + addOnPickups,
        ),
        mintCounter = region.mintCounter + inputs.dropoffCount + addOnPickups.size,
    )
}

/**
 * #526 D2: pre-create one PICKUP placeholder per **distinct store** an accepted offer covers, the
 * symmetric counterpart to [preCreatedDropoffs]. Two orders at the *same* store are ONE combined
 * pickup on DoorDash (the #499 fold-in), so [distinctStoreHints] must already be de-duplicated.
 * Each placeholder carries its order's [Task.expectedStoreHint]; the authoritative [Task.storeName]
 * is filled when a pickup screen resolves onto it. ids are minted at [startOffset]..[startOffset]+
 * size-1; the caller advances the counter in the same copy().
 */
internal fun PlatformRegionStepper.preCreatedPickups(
    region: PlatformRegion,
    obs: Observation,
    jobId: String,
    distinctStoreHints: List<String>,
    startOffset: Long,
): List<Task> = distinctStoreHints.mapIndexed { i, hint ->
    Task(
        taskId = mintId("task", region, obs, offset = startOffset + i),
        jobId = jobId,
        phase = TaskPhase.PICKUP,
        expectedStoreHint = hint,
        storeName = null,
        startedAt = obs.timestamp,
    )
}

/** De-duplicate raw offer store hints case-insensitively, preserving first-seen order. */
internal fun PlatformRegionStepper.distinctStoreHints(storeHints: List<String>): List<String> =
    storeHints.map { it.trim() }.filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }

/**
 * #526 D4a: repair a mis-bound pickup slot. If the active PICKUP task's parsed store this frame
 * bestMatches (>=1 shared leading token) exactly ONE OTHER open pickup placeholder's hint AND
 * shares 0 leading tokens with the active task's own hint, the accumulation is on the wrong order
 * slot — swap it onto the correctly-hinted slot (identity-preserving, no re-mint) and return the
 * post-swap job + the now-correct active task. Otherwise returns `(region.activeJob, activeTask)`
 * unchanged. Conservative: unique match + clean 0-token divergence only; ambiguous → no swap.
 * Both slots must be OPEN (the active task by definition; the other via `storeName == null &&
 * completedAt == null`) — the D4 lifecycle contract that keeps [swapTaskAccumulation]'s timestamp
 * swap safe.
 */
internal fun PlatformRegionStepper.maybeSwapMisboundPickup(
    region: PlatformRegion,
    activeTask: Task,
    taskPhase: TaskPhase,
    taskFields: ParsedFields.TaskFields?,
): Pair<Job?, Task> {
    val noSwap = region.activeJob to activeTask
    if (taskPhase != TaskPhase.PICKUP || activeTask.completedAt != null) return noSwap
    val job = region.activeJob ?: return noSwap
    if (job.tasks.none { it.taskId == activeTask.taskId }) return noSwap
    val screenStore = taskFields?.storeName?.trim()?.takeIf { it.isNotEmpty() } ?: return noSwap
    val displacedIds = region.recentTasks.mapTo(HashSet()) { it.taskId }
    val others = job.tasks.filter {
        it.phase == TaskPhase.PICKUP && it.storeName == null && it.completedAt == null &&
            it.taskId != activeTask.taskId && it.taskId !in displacedIds &&
            it.expectedStoreHint != null
    }
    val match = others
        .filter { StoreNameMatch.sharedLeadingTokens(screenStore, it.expectedStoreHint!!) >= 1 }
        .singleOrNull() ?: return noSwap
    // #526 FIX3a: the 0-token divergence must be measured against SOMETHING the active slot owns.
    // Prefer its offer hint; fall back to its accumulated storeName when the hint is null (a
    // bare-fallback pickup). If the active slot owns NEITHER a hint nor a store, there is no
    // basis to claim divergence — never swap (a null hint made the check vacuously true, causing
    // wrong swaps).
    val ownAnchor = activeTask.expectedStoreHint ?: activeTask.storeName ?: return noSwap
    if (StoreNameMatch.sharedLeadingTokens(screenStore, ownAnchor) >= 1) return noSwap
    val swappedJob = job.withSwappedAccumulation(activeTask.taskId, match.taskId)
    val newActive = swappedJob.tasks.firstOrNull { it.taskId == match.taskId } ?: return noSwap
    return swappedJob to newActive
}

