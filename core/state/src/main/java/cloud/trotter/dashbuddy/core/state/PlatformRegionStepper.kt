package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.ratings.RatingsSnapshot
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.SessionType
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.domain.state.TransitionKind
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.domain.state.UNKNOWN_STORE

/**
 * Region 2+ stepper — per-platform durable state.
 *
 * Manages **screen-authoritative** mode transitions, session lifecycle with
 * grace periods, job lifecycle (offer → tasks → completion), and task lifecycle
 * (navigation → arrival → completion).
 *
 * Key principles:
 * - **Screens are authoritative.** A screen observation that implies a mode
 *   change applies it immediately — no threshold counting.
 * - **Clicks record user intent.** They participate in flow/lifecycle updates
 *   but do NOT drive mode transitions.
 * - **Session grace.** When transitioning to Offline (except via SessionEnded),
 *   the session is preserved with a grace deadline. If mode returns to Online
 *   within the grace window, the same session resumes.
 */
@Singleton
class PlatformRegionStepper @Inject constructor() {

    companion object {
        /** Max completed tasks retained per platform during a session. */
        const val MAX_RECENT_TASKS = 20
    }

    /**
     * Deterministic entity-id mint (#344): derived only from replay-stable inputs
     * (platform, obs.timestamp, [PlatformRegion.mintCounter]) so crash-recovery
     * replay reproduces the live run's IDs. UUID.randomUUID broke effect
     * idempotency keys ("start_session:$sessionId") and diverged restored state
     * from already-persisted rows. Every mint MUST bump mintCounter in the same
     * copy() — use [offset] when minting more than one id per observation.
     */
    private fun mintId(
        kind: String,
        region: PlatformRegion,
        obs: Observation,
        offset: Long = 0,
    ): String = "$kind-${region.platform.wire}-${obs.timestamp}-${region.mintCounter + offset}"

    fun step(
        prev: PlatformRegion,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation,
        policy: TransitionPolicy,
    ): PlatformRegion = reconcileJobTasks(stepCore(prev, prevFlow, nextFlow, obs, policy))

    /**
     * Step 1 of the #503 job-container re-model (additive): mirror the active job's task lineage
     * onto [Job.tasks] after every core step — the completed tasks still in [PlatformRegion.recentTasks]
     * plus the active task. Pure derivation from existing region state; nothing reads [Job.tasks] yet,
     * so this changes no behavior. Later slices make the list authoritative (resume from it; create
     * dropoff subtasks onto it from the offer).
     */
    private fun reconcileJobTasks(region: PlatformRegion): PlatformRegion {
        val job = region.activeJob ?: return region
        val lineage = region.recentTasks.filter { it.jobId == job.jobId } +
            listOfNotNull(region.activeTask?.takeIf { it.jobId == job.jobId })
        val lineageIds = lineage.mapTo(HashSet()) { it.taskId }
        // #503 slice 3: preserve pre-created (offer-spawned, not-yet-activated) subtasks — those on
        // the job but not yet in the active/completed lineage — then the lineage. Once an expected
        // subtask is activated its taskId enters the lineage, so it isn't double-listed.
        val pending = job.tasks.filter { it.taskId !in lineageIds && it.completedAt == null }
        val jobTasks = pending + lineage
        return if (job.tasks == jobTasks) region else region.copy(activeJob = job.copy(tasks = jobTasks))
    }

    private fun stepCore(
        prev: PlatformRegion,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation,
        policy: TransitionPolicy,
    ): PlatformRegion {
        var current = prev

        // Lazy expiry: a pending destructive transition (dash-end / task-retire)
        // whose deadline has passed is confirmed — committed now. Driven by
        // obs.timestamp (never a wall clock) so crash-recovery replay matches.
        // Runs BEFORE the timeout branch: a routed timeout (#342) is exactly the
        // kind of non-flow observation that must be able to commit an overdue
        // provisional transition.
        current.pendingDestructive?.let { pend ->
            if (obs.timestamp > pend.deadline) {
                // Commit stamped at pend.since — the obs.timestamp of the signal
                // that armed the grace — not the deadline: the dash/task really
                // ended when the destructive signal appeared, the grace only
                // delayed our belief in it (#431).
                current = commitDestructive(current, pend.kind, pend.since)
            }
        }

        // Handle timeout-driven transitions
        if (obs is Observation.Timeout) return handleTimeout(current, obs, policy)

        // Authoritative dash-start signal (#279-B): the set-end-time screen while a
        // dash-end is pending in its grace window means the old dash really ended
        // and a new one is starting — commit the end now so the next Online mints
        // a fresh session instead of resuming the old one.
        current.pendingDestructive?.let { pend ->
            if (pend.kind == DestructiveKind.SESSION_END) {
                val startParsed = (obs as? Observation.FlowObservation)?.parsed
                if ((startParsed as? ParsedFields.IdleFields)?.startingSession == true) {
                    // Honest end time = when the destructive signal appeared (#431).
                    current = endSession(current, pend.since)
                }
            }
        }

        val flowObs = obs as? Observation.FlowObservation ?: return current

        // Resolve what mode this observation implies
        val impliedMode = policy.resolveMode(flowObs.flow, flowObs.modeHint)

        val afterMode = when {
            // No mode signal
            impliedMode == null -> current.copy(lastObservedAt = obs.timestamp)

            // Same mode — confirmed
            impliedMode == current.mode -> current.copy(
                lastObservedAt = obs.timestamp,
                lastTransitionKind = TransitionKind.Confirmed,
            )

            // Click — records user intent, does NOT drive mode
            flowObs is Observation.Click -> current.copy(lastObservedAt = obs.timestamp)

            // Screen or Notification — authoritative, apply immediately
            else -> {
                val kind = policy.classify(
                    current.mode, impliedMode, flowObs.expectedOutcomes, flowObs,
                )
                val transitioned = applyModeTransition(current, impliedMode, obs, kind, policy)
                // Heal lifecycle when coming Online from Offline (app restart
                // mid-task) or on any Unexpected transition. healActiveLifecycle
                // self-guards: only acts if the flow is a task flow with no
                // active job, so it's safe to call broadly.
                if (kind == TransitionKind.Unexpected ||
                    (current.mode == Mode.Offline && impliedMode == Mode.Online)) {
                    healActiveLifecycle(transitioned, obs)
                } else {
                    transitioned
                }
            }
        }

        // Update session/job/task lifecycle based on flow changes
        return updateLifecycle(afterMode, prevFlow, nextFlow, flowObs, policy)
    }

    // =========================================================================
    // MODE TRANSITIONS
    // =========================================================================

    private fun applyModeTransition(
        prev: PlatformRegion,
        newMode: Mode,
        obs: Observation,
        kind: TransitionKind,
        policy: TransitionPolicy,
    ): PlatformRegion {
        var region = prev.copy(
            mode = newMode,
            lastObservedAt = obs.timestamp,
            lastTransitionKind = kind,
        )

        // Session lifecycle on mode transitions. An authoritative `session:ended`
        // is committed mode-independently in updateLifecycle (covers both the
        // before-idle and after-idle orderings), so it isn't special-cased here.
        when {
            prev.mode != Mode.Online && newMode == Mode.Online -> {
                val pend = region.pendingDestructive
                if (pend?.kind == DestructiveKind.SESSION_END && !pend.authoritative &&
                    region.session != null
                ) {
                    // Grace active + the same session is still present → a genuine
                    // resume (a transient offline flash). A real new-dash start
                    // would already have committed the end in step() (startingSession).
                    // An AUTHORITATIVE pending (armed by the dash summary, #431) is
                    // deliberately NOT cancelled here: a post-summary online flash
                    // must not resurrect a really-ended session. Only a task-flow
                    // observation cancels it (updateTaskLifecycle).
                    region = region.copy(pendingDestructive = null)
                } else if (region.session == null) {
                    // No session — start a fresh one.
                    region = region.copy(
                        session = Session(
                            sessionId = mintId("session", region, obs),
                            startedAt = obs.timestamp,
                        ),
                        mintCounter = region.mintCounter + 1,
                    )
                }
            }
            prev.mode != Mode.Offline && newMode == Mode.Offline -> {
                // Non-authoritative offline — arm a provisional dash-end, keeping
                // the session alive until it's confirmed or cancelled.
                if (region.session != null &&
                    region.pendingDestructive?.kind != DestructiveKind.SESSION_END
                ) {
                    region = region.copy(
                        pendingDestructive = PendingDestructive(
                            kind = DestructiveKind.SESSION_END,
                            since = obs.timestamp,
                            deadline = obs.timestamp + policy.gracePeriodMs,
                        ),
                    )
                }
            }
        }

        return region
    }

    /**
     * When an unexpected transition fires (e.g., app launched mid-pickup),
     * synthesize missing lifecycle entities so the state is consistent.
     */
    private fun healActiveLifecycle(region: PlatformRegion, obs: Observation): PlatformRegion {
        val flowObs = obs as? Observation.FlowObservation ?: return region
        val flow = flowObs.flow ?: return region

        // If we're healing into an active task flow but have no active job/task,
        // synthesize them with recovered=true
        if (flow.isTaskFlow() && region.activeJob == null) {
            val taskPhase = flow.toTaskPhase() ?: return region
            val parsed = flowObs.parsed
            val storeName = (parsed as? ParsedFields.TaskFields)?.storeName

            val jobId = mintId("job", region, obs)
            val taskId = mintId("task", region, obs, offset = 1)

            return region.copy(
                activeJob = Job(
                    jobId = jobId,
                    offerStoreHint = listOfNotNull(storeName),
                    parentOfferHash = null, // unknown — healed
                    startedAt = obs.timestamp,
                ),
                activeTask = Task(
                    taskId = taskId,
                    jobId = jobId,
                    phase = taskPhase,
                    storeName = storeName,
                    startedAt = obs.timestamp,
                    recovered = true,
                ),
                mintCounter = region.mintCounter + 2,
            )
        }

        return region
    }

    // =========================================================================
    // TIMEOUT HANDLING
    // =========================================================================

    private fun handleTimeout(
        prev: PlatformRegion,
        obs: Observation.Timeout,
        policy: TransitionPolicy,
    ): PlatformRegion {
        return when (obs.type) {
            TimeoutType.SESSION_PAUSED_SAFETY -> {
                // Pause timer expired — transition to offline via applyModeTransition
                // so it gets grace treatment
                if (prev.mode == Mode.Paused) {
                    applyModeTransition(
                        prev, Mode.Offline, obs, TransitionKind.Expected, policy,
                    )
                } else prev
            }
            else -> prev // Automation timeouts handled by EffectMap
        }
    }

    // =========================================================================
    // LIFECYCLE MANAGEMENT
    // =========================================================================

    /**
     * Update session/job/task based on flow transitions.
     */
    private fun updateLifecycle(
        region: PlatformRegion,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation.FlowObservation,
        policy: TransitionPolicy,
    ): PlatformRegion {
        // Authoritative session end: the dash-summary screen. It no longer ends
        // the session on the spot (#431) — one misrecognized frame used to split
        // a live session irrecoverably. Instead it arms (or tightens) a SHORT
        // authoritative grace: a contradicting task-flow frame inside the window
        // cancels (misrecognition), anything else lets it commit at the deadline
        // (GRACE_COMMIT timer or lazy expiry). The summary's parsed fields ride
        // the pending so the deferred DASH_STOP payload keeps full fidelity.
        // Runs before the Offline early-return below because the summary commonly
        // shows AFTER the idle/offline screen, mid-grace.
        if (obs.flow == Flow.SessionEnded && region.session != null) {
            val endFields = obs.parsed as? ParsedFields.SessionEndedFields
            val newDeadline = obs.timestamp + policy.authoritativeGraceMs
            val existing = region.pendingDestructive
            val pend = if (existing?.kind == DestructiveKind.SESSION_END) {
                // Offline-grace already armed (idle/offline before summary) —
                // tighten to the short window, keep the original `since` (the
                // earliest destructive signal is the honest end time).
                existing.copy(
                    deadline = minOf(existing.deadline, newDeadline),
                    authoritative = true,
                    endFields = endFields ?: existing.endFields,
                )
            } else {
                PendingDestructive(
                    kind = DestructiveKind.SESSION_END,
                    since = obs.timestamp,
                    deadline = newDeadline,
                    authoritative = true,
                    endFields = endFields,
                )
            }
            return region.copy(pendingDestructive = pend)
        }
        if (region.mode == Mode.Offline) {
            // Clear the idle anchor and any TASK_RETIRE pending, but PRESERVE a
            // SESSION_END pending — that IS the graced-offline state.
            val keptPending = region.pendingDestructive
                ?.takeIf { it.kind == DestructiveKind.SESSION_END }
            return region.copy(idleEnteredAt = null, pendingDestructive = keptPending)
        }

        var r = region
        val prev = prevFlow.flow
        val next = nextFlow.flow

        // Update session fields from observations
        r = updateSessionFields(r, obs)

        // Accumulate delivery pay when entering PostTask
        if (prev != Flow.PostTask && next == Flow.PostTask) {
            val postFields = obs.parsed as? ParsedFields.PostTaskFields
            if (postFields != null && postFields.totalPay > 0) {
                r.session?.let { session ->
                    val accumulated = session.accumulatedDeliveryPay + postFields.totalPay
                    val best = maxOf(session.runningEarnings, accumulated)
                    r = r.copy(session = session.copy(
                        accumulatedDeliveryPay = accumulated,
                        runningEarnings = best,
                    ))
                }
            }
        }

        // Update ratings if we got a ratings observation
        r = updateRatings(r, obs)

        // Job lifecycle
        r = updateJobLifecycle(r, prev, next, prevFlow, nextFlow, obs)

        // Task lifecycle
        r = updateTaskLifecycle(r, prev, next, obs, policy)

        // Idle anchor: track when we started waiting for offers
        r = when {
            next == Flow.Idle && r.mode == Mode.Online && r.idleEnteredAt == null ->
                r.copy(idleEnteredAt = obs.timestamp)
            (next != Flow.Idle || r.mode != Mode.Online) && r.idleEnteredAt != null ->
                r.copy(idleEnteredAt = null)
            else -> r
        }

        return r
    }

    private fun updateSessionFields(region: PlatformRegion, obs: Observation.FlowObservation): PlatformRegion {
        val parsed = obs.parsed
        var r = region

        when (parsed) {
            is ParsedFields.IdleFields -> {
                if (parsed.zoneName != null) r = r.copy(zoneName = parsed.zoneName)
                if (parsed.sessionType != null) r = r.copy(sessionType = parsed.sessionType)
                r.session?.let { session ->
                    val pay = parsed.sessionPay
                    if (pay != null) {
                        r = r.copy(session = session.copy(runningEarnings = pay))
                    }
                }
            }
            is ParsedFields.PostTaskFields -> {
                val payHash = parsed.parsedPay?.hashCode()
                // Stamp the per-task announcement gate so EffectMap.diffPostTask
                // can detect "first time seeing PostTask for this taskId". The
                // completing task is the still-active one while its retire
                // grace is pending (#431 pt 2), falling back to the last
                // committed task. MUST be the same resolution diffPostTask
                // uses — the old recentTasks-only stamp lagged the commit by
                // one frame and double-fired the receipt bubble on the
                // expanded re-observation.
                val postTaskTaskId = r.activeTask?.taskId ?: r.recentTasks.lastOrNull()?.taskId
                r = r.copy(
                    lastPostTaskPayHash = payHash,
                    lastPostTaskFields = parsed,
                    lastAnnouncedPostTaskTaskId = postTaskTaskId ?: r.lastAnnouncedPostTaskTaskId,
                )
                r.session?.let { session ->
                    val earnings = parsed.sessionEarnings
                    if (earnings != null) {
                        r = r.copy(session = session.copy(runningEarnings = earnings))
                    }
                }
            }
            is ParsedFields.SessionEndedFields -> {
                r.session?.let { session ->
                    r = r.copy(session = session.copy(runningEarnings = parsed.totalEarnings))
                }
            }
            is ParsedFields.PausedFields -> {
                // Pause fields are handled by mode inference, not session
            }
            else -> { /* no session updates */ }
        }

        return r
    }

    private fun updateRatings(region: PlatformRegion, obs: Observation.FlowObservation): PlatformRegion {
        val parsed = obs.parsed as? ParsedFields.RatingsFields ?: return region
        return region.copy(
            ratings = RatingsSnapshot(
                capturedAt = obs.timestamp,
                acceptanceRate = parsed.acceptanceRate,
                completionRate = parsed.completionRate,
                onTimeRate = parsed.onTimeRate,
                customerRating = parsed.customerRating,
                deliveriesLast30Days = parsed.deliveriesLast30Days,
                lifetimeDeliveries = parsed.lifetimeDeliveries,
                originalItemsFoundRate = parsed.originalItemsFoundRate,
                totalItemsFoundRate = parsed.totalItemsFoundRate,
                substitutionIssuesRate = parsed.substitutionIssuesRate,
                itemsWithQualityIssuesRate = parsed.itemsWithQualityIssuesRate,
                itemsWrongOrMissingRate = parsed.itemsWrongOrMissingRate,
                lifetimeShoppingOrders = parsed.lifetimeShoppingOrders,
            ),
        )
    }

    private fun updateJobLifecycle(
        region: PlatformRegion,
        prevFlowVal: Flow,
        nextFlowVal: Flow,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation.FlowObservation,
    ): PlatformRegion {
        // Offer accepted → capture its economics onto the job. A first accept STARTS a new
        // Job; an add-on accepted while a job is already active APPENDS (so stacked pay,
        // time, and distance accumulate) rather than being silently dropped. Deduped by
        // offerHash so a re-entered OfferPresented for the same offer (screen oscillation)
        // does not double-count.
        if (prevFlowVal == Flow.OfferPresented && nextFlowVal.isTaskFlow()) {
            val pending = prevFlow.pendingOffer
            val parsedOffer = pending?.offerFields?.parsedOffer
            val eval = pending?.evaluation
            val economics = AcceptedOfferEconomics(
                offerHash = pending?.offerHash,
                payAmount = eval?.payAmount ?: parsedOffer?.payAmount,
                netPay = eval?.netPayAmount,
                estMinutes = eval?.estimatedTimeMinutes ?: parsedOffer?.timeToCompleteMinutes?.toDouble(),
                distanceMiles = eval?.distanceMiles ?: parsedOffer?.distanceMiles,
                acceptedAt = obs.timestamp,
            )
            val storeHints = parsedOffer?.orders?.map { it.storeName } ?: emptyList()
            val existing = region.activeJob

            if (existing == null) {
                val jobId = mintId("job", region, obs)
                return region.copy(
                    activeJob = Job(
                        jobId = jobId,
                        offerStoreHint = storeHints,
                        parentOfferHash = pending?.offerHash,
                        acceptedOffers = listOf(economics),
                        startedAt = obs.timestamp,
                        // #503 slice 3: pre-create the offer's dropoff subtask (customer TBD). The
                        // dropoff screen later RESOLVES the customer onto this subtask instead of
                        // minting a fresh dropoff — the root fix for the premature "Customer" card
                        // (a dropoff surfaced before its customer resolves).
                        tasks = listOf(
                            Task(
                                taskId = mintId("task", region, obs, offset = 1),
                                jobId = jobId,
                                phase = TaskPhase.DROPOFF,
                                customerNameHash = null,
                                startedAt = obs.timestamp,
                            ),
                        ),
                    ),
                    mintCounter = region.mintCounter + 2,
                )
            }

            // Add-on into the active job — append unless this offer was already counted.
            val alreadyCounted = economics.offerHash != null &&
                existing.acceptedOffers.any { it.offerHash == economics.offerHash }
            if (alreadyCounted) return region
            return region.copy(
                activeJob = existing.copy(
                    acceptedOffers = existing.acceptedOffers + economics,
                    offerStoreHint = existing.offerStoreHint + storeHints,
                ),
            )
        }

        // Job start: task flow without active job (recovery or mid-session restart)
        if (nextFlowVal.isTaskFlow() && region.activeJob == null) {
            val parsed = obs.parsed as? ParsedFields.TaskFields
            return region.copy(
                activeJob = Job(
                    jobId = mintId("job", region, obs),
                    offerStoreHint = listOfNotNull(parsed?.storeName),
                    parentOfferHash = null,
                    startedAt = obs.timestamp,
                ),
                mintCounter = region.mintCounter + 1,
            )
        }

        // Post-task: keep job alive through PostTask for payout capture
        // Job ends when we leave PostTask for non-task flow
        if (prevFlowVal == Flow.PostTask && !nextFlowVal.isTaskFlow() && nextFlowVal != Flow.PostTask && nextFlowVal != Flow.OfferPresented) {
            return completeActiveJob(region)
        }

        return region
    }

    private fun updateTaskLifecycle(
        region: PlatformRegion,
        prevFlowVal: Flow,
        nextFlowVal: Flow,
        obs: Observation.FlowObservation,
        policy: TransitionPolicy,
    ): PlatformRegion {
        val parsed = obs.parsed

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
                // #503 slice 2: returning to a prior subtask of this job (A→B→A, or back to a
                // store after an offer interlude that retired the task) RESUMES its identity
                // instead of re-minting — unless the platform signalled a genuinely-new stacked
                // task (different store/customer, already arrived). Re-match by phase + store
                // (pickup) / customer address (dropoff); this is the same-store add-on "fold in,
                // don't re-mint" (#499). The single activeTask slot loses identity on every phase
                // switch; the Job's task lineage in recentTasks lets us restore it.
                val resumable = if (!isStackedPickupTransition && !isStackedDropoffTransition) {
                    region.recentTasks.lastOrNull { prior ->
                        prior.jobId == jobId && prior.phase == taskPhase &&
                            when (taskPhase) {
                                TaskPhase.PICKUP ->
                                    prior.storeName != null && prior.storeName == taskFields?.storeName
                                TaskPhase.DROPOFF ->
                                    prior.customerAddressHash != null &&
                                        prior.customerAddressHash == taskFields?.customerAddressHash
                                else -> false
                            }
                    }
                } else null

                if (resumable != null) {
                    val retireSince = region.pendingDestructive
                        ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }?.since
                    val displaced = currentTask?.copy(completedAt = retireSince ?: obs.timestamp)
                    val justArrived = taskSubFlow == TaskSubFlow.ARRIVED && resumable.arrivedAt == null
                    return region.copy(
                        activeTask = resumable.copy(
                            subPhase = taskSubFlow,
                            completedAt = null,
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
                            listOfNotNull(displaced)).takeLast(MAX_RECENT_TASKS),
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
                        recentTasks = (region.recentTasks + listOfNotNull(displaced)).takeLast(MAX_RECENT_TASKS),
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
                    (region.recentTasks + completedTask).takeLast(MAX_RECENT_TASKS)
                } else region.recentTasks

                return region.copy(
                    activeTask = Task(
                        taskId = mintId("task", region, obs),
                        jobId = jobId,
                        phase = taskPhase,
                        subPhase = taskSubFlow,
                        storeName = taskFields?.storeName ?: currentTask?.storeName,
                        storeAddress = taskFields?.storeAddress ?: currentTask?.storeAddress,
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

            // Same phase — update fields
            val justArrived = taskSubFlow == TaskSubFlow.ARRIVED && currentTask.arrivedAt == null
            return region.copy(
                activeTask = currentTask.copy(
                    subPhase = taskSubFlow,
                    storeName = taskFields?.storeName ?: currentTask.storeName,
                    storeAddress = taskFields?.storeAddress ?: currentTask.storeAddress,
                    customerNameHash = taskFields?.customerNameHash ?: currentTask.customerNameHash,
                    customerAddressHash = taskFields?.customerAddressHash ?: currentTask.customerAddressHash,
                    deadlineMillis = taskFields?.deadline?.time ?: currentTask.deadlineMillis,
                    activity = taskFields?.activity ?: currentTask.activity,
                    itemsRemaining = taskFields?.itemsRemaining ?: currentTask.itemsRemaining,
                    itemsShopped = taskFields?.itemsShopped ?: currentTask.itemsShopped,
                    redCardTotal = taskFields?.redCardTotal ?: currentTask.redCardTotal,
                    arrivedAt = if (justArrived) obs.timestamp else currentTask.arrivedAt,
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
            val newDeadline = obs.timestamp + policy.authoritativeGraceMs
            val existing = region.pendingDestructive
            val pend = if (existing?.kind == DestructiveKind.TASK_RETIRE) {
                // Already armed (idle-grace, or an earlier receipt frame) —
                // tighten to the short window; the earliest destructive signal
                // stays the honest completion time.
                existing.copy(
                    deadline = minOf(existing.deadline, newDeadline),
                    authoritative = true,
                )
            } else {
                PendingDestructive(
                    kind = DestructiveKind.TASK_RETIRE,
                    since = obs.timestamp,
                    deadline = newDeadline,
                    authoritative = true,
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
                        // Through the injected policy (#406): the static constant ignored overrides.
                        deadline = obs.timestamp + policy.gracePeriodMs,
                    ),
                )
            }
        }

        return region
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /** Commit a pending destructive transition — its deadline lapsed, or an
     *  authoritative signal confirmed it. */
    private fun commitDestructive(
        region: PlatformRegion,
        kind: DestructiveKind,
        timestamp: Long,
    ): PlatformRegion = when (kind) {
        DestructiveKind.SESSION_END -> endSession(region, timestamp)
        DestructiveKind.TASK_RETIRE -> retireActiveTask(region, timestamp)
    }

    /**
     * Retire the active task when its retire-grace deadline lazily expires
     * (a sustained idle/offer mid-task). Completes it into recentTasks. Unlike
     * [endSession], the session and job live on — only the task is closed.
     */
    private fun retireActiveTask(region: PlatformRegion, timestamp: Long): PlatformRegion {
        val completed = region.activeTask?.copy(completedAt = timestamp)
            ?: return region.copy(pendingDestructive = null)
        return region.copy(
            activeTask = null,
            recentTasks = (region.recentTasks + completed).takeLast(MAX_RECENT_TASKS),
            pendingDestructive = null,
        )
    }

    private fun endSession(region: PlatformRegion, timestamp: Long): PlatformRegion {
        val completedTask = region.activeTask?.copy(completedAt = timestamp)
        val recentTasks = if (completedTask != null) {
            (region.recentTasks + completedTask).takeLast(MAX_RECENT_TASKS)
        } else region.recentTasks

        return region.copy(
            session = null,
            activeJob = null,
            activeTask = null,
            recentTasks = recentTasks,
            pendingDestructive = null,
            idleEnteredAt = null,
            lastPostTaskPayHash = null,
            lastPostTaskFields = null,
        )
    }

    private fun completeActiveJob(region: PlatformRegion): PlatformRegion {
        val job = region.activeJob ?: return region
        return region.copy(
            activeJob = null,
            lastPostTaskPayHash = null,
            lastPostTaskFields = null,
        )
    }
}

// =========================================================================
// FLOW EXTENSION HELPERS
// =========================================================================

internal fun Flow.isTaskFlow(): Boolean = this in setOf(
    Flow.TaskPickupNavigation,
    Flow.TaskPickupArrived,
    Flow.TaskDropoffNavigation,
    Flow.TaskDropoffArrived,
)

internal fun Flow.toTaskPhase(): TaskPhase? = when (this) {
    Flow.TaskPickupNavigation, Flow.TaskPickupArrived -> TaskPhase.PICKUP
    Flow.TaskDropoffNavigation, Flow.TaskDropoffArrived -> TaskPhase.DROPOFF
    else -> null
}

internal fun Flow.toTaskSubFlow(): TaskSubFlow? = when (this) {
    Flow.TaskPickupNavigation, Flow.TaskDropoffNavigation -> TaskSubFlow.NAVIGATION
    Flow.TaskPickupArrived, Flow.TaskDropoffArrived -> TaskSubFlow.ARRIVED
    else -> null
}
