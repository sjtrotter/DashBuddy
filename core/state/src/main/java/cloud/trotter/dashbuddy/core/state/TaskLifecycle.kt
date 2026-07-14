package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.StoreNameMatch
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.domain.state.UNKNOWN_STORE

/**
 * The per-task lifecycle arm ([updateTaskLifecycle]), extracted from [PlatformRegionStepper] (#761 —
 * the #237 file-ceiling residue; [OfferLifecycle] / [JobAcceptFlow] are the same-package #718
 * precedent). A pure move, no logic change: it stays an `internal` extension on
 * [PlatformRegionStepper] — called by `updateLifecycle` and sharing [PlatformRegionStepper.mintId],
 * `abandonActiveTask` ([TaskLineage]), and `maybeSwapMisboundPickup` ([JobAcceptFlow]) via the
 * implicit receiver, plus [PlatformRegionStepper.MAX_RECENT_TASKS] (explicitly qualified — an
 * extension cannot see companion scope).
 *
 * Everything else — the mode/grace/session/job arms, `mintId`, `completeActiveJob`,
 * `retireActiveTask`, `endSession` — deliberately stays in `PlatformRegionStepper.kt`; the lineage
 * family lives in the sibling `TaskLineage.kt`.
 */
internal fun PlatformRegionStepper.updateTaskLifecycle(
    region: PlatformRegion,
    prevFlowVal: Flow,
    nextFlowVal: Flow,
    obs: Observation.FlowObservation,
    policy: TransitionPolicy,
): PlatformRegion {
    val parsed = obs.parsed

    // #736: the platform's own authoritative statement that the dasher UNASSIGNED the order
    // (task:unassigned). Not a task flow — an inline (ungraced) abandon of the active task. A
    // grace is wrong here: the anchor is a single-purpose platform sentence (no misrecognition
    // flap to debounce), and a grace would let a legitimate "continue to your next stop" frame
    // inside the window cancel the pending and stamp completedAt — re-creating the fabrication.
    // Self-heal (the resume copy sites clear unassignedAt) covers a genuine misread — but ONLY
    // while the job stayed OPEN (the multi-dropoff / no-hash arms); after a single-order abandon
    // the job closes and a later same-order frame mints a NEW jobId the resume lookup can't reach.
    // Documented residual (#736 review), not widened here.
    if (nextFlowVal == Flow.TaskUnassigned) {
        return abandonActiveTask(region, obs.timestamp, prevFlowVal)
    }

    // Task flow → create or update active task
    if (nextFlowVal.isTaskFlow()) {
        val taskPhase = nextFlowVal.toTaskPhase() ?: return region
        val taskSubFlow = nextFlowVal.toTaskSubFlow() ?: return region
        val taskFields = parsed as? ParsedFields.TaskFields

        val currentTask = region.activeTask
        val jobId = region.activeJob?.jobId ?: return region

        // Phase/subflow change → new task or update.
        //
        // Stacked-pickup transition: same PICKUP phase, but the platform has
        // routed us from a confirmed-arrived store back to navigation toward
        // a different store. Treat as a new task so the second pickup gets
        // its own taskId, its own odometer leg, and its own flow-card.
        //
        // Three signals combined for robustness: we must have already
        // arrived at the previous store; the new screen must be a
        // navigation/pre-arrival (subflow=NAVIGATION); and the parsed
        // storeName must be a different, non-Unknown name. Any single
        // signal alone is vulnerable to parser flakiness (notably the
        // pickup_arrival storeName parser, which picks the wrong
        // instructions_title on multi-node screens — see the field-test
        // 2026-05-17 Chili's capture).
        val isStackedPickupTransition = currentTask != null &&
            currentTask.phase == TaskPhase.PICKUP &&
            taskPhase == TaskPhase.PICKUP &&
            currentTask.arrivedAt != null &&
            taskSubFlow == TaskSubFlow.NAVIGATION &&
            taskFields?.storeName != null &&
            !taskFields.storeName.equals(UNKNOWN_STORE, ignoreCase = true) &&
            taskFields.storeName != currentTask.storeName

        // Stacked-dropoff transition: symmetric to the pickup case but keyed
        // on customerAddressHash — arrived at one customer, now navigating to
        // a different one. Lets back-to-back dropoffs mint distinctly even if
        // no post:task is observed between them.
        val isStackedDropoffTransition = currentTask != null &&
            currentTask.phase == TaskPhase.DROPOFF &&
            taskPhase == TaskPhase.DROPOFF &&
            currentTask.arrivedAt != null &&
            taskSubFlow == TaskSubFlow.NAVIGATION &&
            // #565: a stacked transition means "arrived at customer A, now navigating to a
            // DIFFERENT customer B" — which is only possible if the active task ALREADY HAD a
            // customer. A null→present customer is the FIRST resolution of a customer-TBD
            // placeholder dropoff (#503 slice 3), not a transition to a new drop. Without this
            // guard, the customer-bearing frame that finally resolves an active, customer-less
            // placeholder (a genuine handoff screen activated it customer-less first) was treated
            // as a stacked transition and fell through to a FRESH mint — orphaning the placeholder
            // as a dead customer-less husk and re-minting the card (06-21 Walgreens noon: task-11
            // placeholder → spurious task-13). A present prior customer keeps real stacks distinct.
            currentTask.customerNameHash != null &&
            taskFields?.customerAddressHash != null &&
            taskFields.customerAddressHash != currentTask.customerAddressHash &&
            // #498 task-path: only a genuinely DIFFERENT customer starts a new stacked dropoff —
            // gate on the stable customer NAME hash, not just the address. An unstable dropoff
            // address parse split one physical drop into two tasks on 06-17 (task-39/-40 carried
            // the same name hash f5b3497a but different address hashes). A present, changed name
            // is required; a null/unchanged name is the same customer, so update, don't re-mint.
            taskFields.customerNameHash != null &&
            taskFields.customerNameHash != currentTask.customerNameHash

        if (currentTask == null ||
            currentTask.phase != taskPhase ||
            isStackedPickupTransition ||
            isStackedDropoffTransition
        ) {
            // #503 slice 2/3b-2: returning to a prior subtask of this job (A→B→A, or back to a
            // store after an offer interlude that retired the task) RESUMES its identity instead
            // of re-minting. The single activeTask slot loses identity on every phase switch; the
            // Job's task lineage in recentTasks lets us restore it.
            //
            // Dropoff is re-matched on the STABLE customer-NAME hash, NOT the address (#498:
            // dropoff addresses drift between frames, so an address key split one physical drop
            // into two). This runs even under a stacked-dropoff transition, so returning to an
            // earlier stacked drop routes to it instead of minting a duplicate. Pickup re-matches
            // by store, but only when the platform did NOT signal a genuinely-new stacked pickup
            // (two distinct orders at the same store must stay distinct — the same-store add-on
            // "fold in, don't re-mint" of #499 is the !isStackedPickupTransition case).
            val resumable = when {
                taskPhase == TaskPhase.DROPOFF && taskFields?.customerNameHash != null ->
                    region.recentTasks.lastOrNull {
                        it.jobId == jobId && it.phase == TaskPhase.DROPOFF &&
                            it.customerNameHash == taskFields.customerNameHash
                    }
                taskPhase == TaskPhase.PICKUP && !isStackedPickupTransition ->
                    region.recentTasks.lastOrNull {
                        it.jobId == jobId && it.phase == TaskPhase.PICKUP &&
                            it.storeName != null && it.storeName == taskFields?.storeName
                    }
                else -> null
            }

            if (resumable != null) {
                val retireSince = region.pendingDestructive
                    ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }?.since
                val displaced = currentTask?.copy(completedAt = retireSince ?: obs.timestamp)
                val justArrived = taskSubFlow == TaskSubFlow.ARRIVED && resumable.arrivedAt == null
                return region.copy(
                    activeTask = resumable.copy(
                        subPhase = taskSubFlow,
                        completedAt = null,
                        // #736 resume self-heal: a genuine same-order frame reactivating a task
                        // that a misread had marked abandoned clears the unassign marker.
                        unassignedAt = null,
                        storeName = taskFields?.storeName ?: resumable.storeName,
                        storeAddress = taskFields?.storeAddress ?: resumable.storeAddress,
                        customerNameHash = taskFields?.customerNameHash ?: resumable.customerNameHash,
                        customerAddressHash = taskFields?.customerAddressHash ?: resumable.customerAddressHash,
                        deadlineMillis = taskFields?.deadline?.time ?: resumable.deadlineMillis,
                        activity = taskFields?.activity ?: resumable.activity,
                        itemsRemaining = taskFields?.itemsRemaining ?: resumable.itemsRemaining,
                        itemsShopped = taskFields?.itemsShopped ?: resumable.itemsShopped,
                        redCardTotal = taskFields?.redCardTotal ?: resumable.redCardTotal,
                        arrivedAt = if (justArrived) obs.timestamp else resumable.arrivedAt,
                    ),
                    recentTasks = (region.recentTasks.filterNot { it.taskId == resumable.taskId } +
                        listOfNotNull(displaced)).takeLast(PlatformRegionStepper.MAX_RECENT_TASKS),
                    pendingDestructive = null,
                )
            }

            // #526 D3: resolve a pickup screen onto a pre-created (offer-spawned) PICKUP
            // placeholder of this job instead of minting — the symmetric counterpart to the
            // dropoff resolution below. Store hints are unreliable, so the priority is:
            //   1. exact case-insensitive hint match,
            //   2. D3a: StoreNameMatch.bestMatch (>=1 shared leading token — the same SSOT
            //      reconcileDropoffStore uses), so a noisy/null store doesn't blind-bind in
            //      offset order,
            //   3. next open placeholder in offset order.
            // "Open" = storeName not yet resolved, not completed, not the current/displaced task.
            // A wrong bind can be repaired later without re-minting via the swap guard (D4a below).
            val expectedPickup = if (taskPhase == TaskPhase.PICKUP) {
                val displacedIds = region.recentTasks.mapTo(HashSet()) { it.taskId }
                val openPickups = region.activeJob?.tasks.orEmpty().filter { p ->
                    p.phase == TaskPhase.PICKUP &&
                        p.storeName == null &&
                        p.completedAt == null &&
                        p.taskId != currentTask?.taskId &&
                        p.taskId !in displacedIds
                }
                val screenStore = taskFields?.storeName?.trim()?.takeIf { it.isNotEmpty() }
                val hintMatch = screenStore?.let { s ->
                    openPickups.firstOrNull { it.expectedStoreHint?.equals(s, ignoreCase = true) == true }
                }
                val bestMatch = if (hintMatch == null && screenStore != null) {
                    val best = StoreNameMatch.bestMatch(openPickups.mapNotNull { it.expectedStoreHint }, screenStore)
                    best?.let { b -> openPickups.firstOrNull { it.expectedStoreHint == b } }
                } else null
                // #526 FIX3b: tier 3 (next-open) binds ONLY when there is exactly ONE open
                // placeholder. With ≥2 open and no parsed/matched store, a blind first-open bind
                // guesses the wrong order — fall through to a fresh mint (master behavior); a
                // later frame carrying a real store can hint-match the right placeholder.
                hintMatch ?: bestMatch ?: openPickups.singleOrNull()
            } else null

            if (expectedPickup != null) {
                val retireSince = region.pendingDestructive
                    ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }?.since
                val displaced = currentTask?.copy(completedAt = retireSince ?: obs.timestamp)
                val justArrived = taskSubFlow == TaskSubFlow.ARRIVED && expectedPickup.arrivedAt == null
                return region.copy(
                    activeTask = expectedPickup.copy(
                        subPhase = taskSubFlow,
                        storeName = taskFields?.storeName ?: expectedPickup.storeName,
                        storeAddress = taskFields?.storeAddress ?: expectedPickup.storeAddress,
                        customerNameHash = taskFields?.customerNameHash ?: expectedPickup.customerNameHash,
                        customerAddressHash = taskFields?.customerAddressHash ?: expectedPickup.customerAddressHash,
                        deadlineMillis = taskFields?.deadline?.time ?: expectedPickup.deadlineMillis,
                        activity = taskFields?.activity,
                        itemsRemaining = taskFields?.itemsRemaining,
                        itemsShopped = taskFields?.itemsShopped,
                        redCardTotal = taskFields?.redCardTotal,
                        startedAt = obs.timestamp,
                        arrivedAt = if (justArrived) obs.timestamp else expectedPickup.arrivedAt,
                    ),
                    recentTasks = (region.recentTasks + listOfNotNull(displaced)).takeLast(PlatformRegionStepper.MAX_RECENT_TASKS),
                    pendingDestructive = null,
                )
            }

            // #503 slice 3: resolve onto a pre-created (offer-spawned, customer-TBD) dropoff
            // subtask of this job instead of minting a fresh dropoff — the dropoff screen
            // RESOLVES the customer onto the subtask the offer created at accept. Fixes the
            // premature "Customer" card / phantom drops (a dropoff that exists before its
            // customer is known).
            val expectedDropoff = if (taskPhase == TaskPhase.DROPOFF) {
                val displacedIds = region.recentTasks.mapTo(HashSet()) { it.taskId }
                region.activeJob?.tasks?.firstOrNull { pending ->
                    pending.phase == TaskPhase.DROPOFF &&
                        pending.customerNameHash == null &&
                        pending.completedAt == null &&
                        pending.taskId != currentTask?.taskId &&
                        pending.taskId !in displacedIds
                }
            } else null

            if (expectedDropoff != null) {
                val retireSince = region.pendingDestructive
                    ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }?.since
                val displaced = currentTask?.copy(completedAt = retireSince ?: obs.timestamp)
                val justArrived = taskSubFlow == TaskSubFlow.ARRIVED && expectedDropoff.arrivedAt == null
                return region.copy(
                    activeTask = expectedDropoff.copy(
                        subPhase = taskSubFlow,
                        storeName = taskFields?.storeName ?: expectedDropoff.storeName,
                        storeAddress = taskFields?.storeAddress ?: expectedDropoff.storeAddress,
                        customerNameHash = taskFields?.customerNameHash,
                        customerAddressHash = taskFields?.customerAddressHash,
                        deadlineMillis = taskFields?.deadline?.time ?: expectedDropoff.deadlineMillis,
                        activity = taskFields?.activity,
                        itemsRemaining = taskFields?.itemsRemaining,
                        itemsShopped = taskFields?.itemsShopped,
                        redCardTotal = taskFields?.redCardTotal,
                        startedAt = obs.timestamp,
                        arrivedAt = if (justArrived) obs.timestamp else expectedDropoff.arrivedAt,
                    ),
                    recentTasks = (region.recentTasks + listOfNotNull(displaced)).takeLast(PlatformRegionStepper.MAX_RECENT_TASKS),
                    pendingDestructive = null,
                )
            }

            // #498 phantom-dropoff guard: a transition INTO a dropoff that carries no
            // customer identity at all (no name hash AND no address hash) is a transient
            // confirmation/geofence/arriving screen — dropoff_completed_confirm,
            // dropoff_geofence_warning, nav_arriving — whose flow is task:dropoff:* but
            // which parses no customer. It is NOT a distinct delivery. Minting a fresh
            // dropoff here produced the "the customer" phantom: an identity-less dropoff
            // that immediately completed (06-17 captures: task-9 on a single H-E-B order,
            // task-13, and task-38 on the Jim's stack — the only cn==null && ca==null
            // dropoffs in the whole session; every real dropoff carried a customer hash).
            // Keep the current task; the customer-bearing dropoff_navigation/pre_arrival
            // frame that follows transitions properly (it just resolves identity first).
            // Resume and resolve-onto-placeholder above are unaffected — both reuse an
            // existing task; this only suppresses the fall-through NEW mint.
            if (taskPhase == TaskPhase.DROPOFF &&
                taskFields?.customerNameHash == null &&
                taskFields?.customerAddressHash == null
            ) {
                return region
            }

            // New task (different phase, no active task, or stacked-pickup transition).
            // The displaced task commits inline; if a TASK_RETIRE grace was
            // pending (receipt or idle already signalled its end, #431 pt 2)
            // the honest completion time is that signal's appearance, not
            // this new task's first frame.
            val retireSince = region.pendingDestructive
                ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }?.since
            val completedTask = currentTask?.copy(completedAt = retireSince ?: obs.timestamp)
            val recentTasks = if (completedTask != null) {
                (region.recentTasks + completedTask).takeLast(PlatformRegionStepper.MAX_RECENT_TASKS)
            } else region.recentTasks

            return region.copy(
                activeTask = Task(
                    taskId = mintId("task", region, obs),
                    jobId = jobId,
                    phase = taskPhase,
                    subPhase = taskSubFlow,
                    // #526: a newly-minted task takes its store from its OWN frame; only inherit
                    // the displaced task's store on a SAME-phase mint (e.g. a stacked-pickup
                    // parser flicker). A cross-phase mint (pickup→dropoff) must NOT inherit — the
                    // dropoff screen carries its own store (`Target (02426)`), and inheriting the
                    // pickup's store mislabelled a multi-store stack's drop (06-19 Target+Maple).
                    // A dropoff with no store frame yet stays null and fills in on its own later
                    // dropoff_pre_arrival frame via the same-phase update path below.
                    storeName = taskFields?.storeName
                        ?: currentTask?.storeName?.takeIf { currentTask.phase == taskPhase },
                    storeAddress = taskFields?.storeAddress
                        ?: currentTask?.storeAddress?.takeIf { currentTask.phase == taskPhase },
                    customerNameHash = taskFields?.customerNameHash,
                    customerAddressHash = taskFields?.customerAddressHash,
                    deadlineMillis = taskFields?.deadline?.time,
                    activity = taskFields?.activity,
                    itemsRemaining = taskFields?.itemsRemaining,
                    itemsShopped = taskFields?.itemsShopped,
                    redCardTotal = taskFields?.redCardTotal,
                    startedAt = obs.timestamp,
                    arrivedAt = if (taskSubFlow == TaskSubFlow.ARRIVED) obs.timestamp else null,
                ),
                recentTasks = recentTasks,
                pendingDestructive = null,
                mintCounter = region.mintCounter + 1,
            )
        }

        // Same phase — update fields.
        //
        // #526 D4a: swap-on-divergence guard. Before accumulating this frame, check whether the
        // active PICKUP's parsed store demonstrably belongs to a DIFFERENT open pickup placeholder
        // — its store bestMatches exactly ONE other open slot's hint (>=1 shared leading token)
        // while sharing 0 leading tokens with the active task's own hint. That means the
        // accumulation is sitting on the wrong order slot: move it onto the correctly-hinted slot
        // without re-minting (the swap primitive keeps both taskIds stable) and continue on the
        // now-correct slot. Conservative — a unique match with a clean 0-token divergence only;
        // ambiguous → no swap. Both tasks are OPEN (the active task by definition; the guard
        // requires the other slot open too — the D4 lifecycle contract).
        val (baseJob, baseTask) = maybeSwapMisboundPickup(region, currentTask, taskPhase, taskFields)
        val justArrived = taskSubFlow == TaskSubFlow.ARRIVED && baseTask.arrivedAt == null
        return region.copy(
            activeJob = baseJob,
            activeTask = baseTask.copy(
                subPhase = taskSubFlow,
                // #736 resume self-heal (defensive twin of the resume path): a live task frame
                // clears any stale unassign marker.
                unassignedAt = null,
                storeName = taskFields?.storeName ?: baseTask.storeName,
                storeAddress = taskFields?.storeAddress ?: baseTask.storeAddress,
                customerNameHash = taskFields?.customerNameHash ?: baseTask.customerNameHash,
                customerAddressHash = taskFields?.customerAddressHash ?: baseTask.customerAddressHash,
                deadlineMillis = taskFields?.deadline?.time ?: baseTask.deadlineMillis,
                activity = taskFields?.activity ?: baseTask.activity,
                itemsRemaining = taskFields?.itemsRemaining ?: baseTask.itemsRemaining,
                itemsShopped = taskFields?.itemsShopped ?: baseTask.itemsShopped,
                redCardTotal = taskFields?.redCardTotal ?: baseTask.redCardTotal,
                arrivedAt = if (justArrived) obs.timestamp else baseTask.arrivedAt,
            ),
            pendingDestructive = null,
        )
    }

    // PostTask → the receipt is authoritative for "this task is done", but
    // it no longer completes the task on the spot (#431 pt 2): one
    // misrecognized receipt frame mid-dropoff used to retire the live task
    // irrecoverably. Arm (or tighten) a SHORT authoritative TASK_RETIRE
    // grace instead — a contradicting task-flow frame inside the window
    // cancels it (misrecognition flap), expiry commits via the GRACE_COMMIT
    // timer / lazy expiry, and a stacked next-task frame commits it inline
    // at mint (above). Replacing a pending SESSION_END here preserves the
    // pre-#431 contract that a receipt-with-active-task reads as "still
    // dashing" (single-slot pending — noted on the issue).
    val postTask = region.activeTask
    if (nextFlowVal == Flow.PostTask && postTask != null) {
        val newDeadline = obs.timestamp + policy.authoritativeGraceMs(region.platform)
        val existing = region.pendingDestructive
        val pend = if (existing?.kind == DestructiveKind.TASK_RETIRE) {
            // Already armed (idle-grace, or an earlier receipt frame) —
            // tighten to the short window; the earliest destructive signal
            // stays the honest completion time. The receipt is an authoritative
            // completion, so re-anchor the provenance to PostTask (#596): a drop
            // finished at a real receipt is closeable even if an earlier
            // offer-deliberation had armed the original retire.
            existing.copy(
                deadline = minOf(existing.deadline, newDeadline),
                authoritative = true,
                armedFromFlow = Flow.PostTask,
            )
        } else {
            PendingDestructive(
                kind = DestructiveKind.TASK_RETIRE,
                since = obs.timestamp,
                deadline = newDeadline,
                authoritative = true,
                armedFromFlow = Flow.PostTask,
            )
        }
        return region.copy(pendingDestructive = pend)
    }

    // Leaving a task flow to idle/offer while online → do NOT retire the task
    // immediately. A transient idle (an informational screen, or the idle map
    // flashing before the delivery summary) must not forget the active task.
    // Arm a grace deadline; returning to a task flow cancels it (above), and a
    // sustained idle past the window retires the task lazily in step().
    if (prevFlowVal.isTaskFlow() && !nextFlowVal.isTaskFlow() && nextFlowVal != Flow.PostTask) {
        if (region.activeTask != null && region.pendingDestructive == null) {
            return region.copy(
                pendingDestructive = PendingDestructive(
                    kind = DestructiveKind.TASK_RETIRE,
                    since = obs.timestamp,
                    // Through the injected policy (#406/#438 item 6): per-platform grace.
                    deadline = obs.timestamp + policy.gracePeriodMs(region.platform),
                    // #596: record where the task was left FOR. A retire armed by the dasher
                    // deliberating on a mid-route add-on offer (`OfferPresented`) must NOT let
                    // T1/T2 close-out fire on the still-undelivered final drop; an idle/waiting
                    // arm (`Idle`) is a genuine receipt-skip and IS closeable.
                    armedFromFlow = nextFlowVal,
                ),
            )
        }
    }

    return region
}
