package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.ratings.RatingsSnapshot
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.SessionType
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.domain.state.TransitionKind
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

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

    fun step(
        prev: PlatformRegion,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation,
        policy: TransitionPolicy,
    ): PlatformRegion {
        // Handle timeout-driven transitions
        if (obs is Observation.Timeout) return handleTimeout(prev, obs, policy)

        // Lazy grace expiration: if grace deadline has passed, end session now
        var current = prev
        if (current.sessionGraceDeadline != null && obs.timestamp > current.sessionGraceDeadline!!) {
            current = endSession(
                current.copy(sessionGraceDeadline = null),
                current.sessionGraceDeadline!!,
            )
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
        return updateLifecycle(afterMode, prevFlow, nextFlow, flowObs)
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

        // Session lifecycle on mode transitions
        when {
            prev.mode != Mode.Online && newMode == Mode.Online -> {
                if (region.sessionGraceDeadline != null && region.session != null) {
                    // Grace active — resume the existing session (clear deadline, keep sessionId)
                    region = region.copy(sessionGraceDeadline = null)
                } else if (region.session == null) {
                    // No grace or no session — create new session
                    region = region.copy(
                        session = Session(
                            sessionId = UUID.randomUUID().toString(),
                            startedAt = obs.timestamp,
                        ),
                    )
                }
            }
            prev.mode != Mode.Offline && newMode == Mode.Offline -> {
                val flow = (obs as? Observation.FlowObservation)?.flow
                if (flow == Flow.SessionEnded) {
                    // Authoritative end signal — end immediately, no grace
                    region = endSession(region, obs.timestamp)
                } else if (region.session != null) {
                    // Non-authoritative offline — grace period, preserve session
                    region = region.copy(
                        sessionGraceDeadline = obs.timestamp + policy.gracePeriodMs,
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

            val jobId = UUID.randomUUID().toString()
            val taskId = UUID.randomUUID().toString()

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
    ): PlatformRegion {
        if (region.mode == Mode.Offline) return region.copy(idleEnteredAt = null)

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
        r = updateTaskLifecycle(r, prev, next, obs)

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
                r = r.copy(lastPostTaskPayHash = payHash, lastPostTaskFields = parsed)
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
        // Job start: offer accepted → pickup begins
        if (prevFlowVal == Flow.OfferPresented && nextFlowVal.isTaskFlow() && region.activeJob == null) {
            val offerFields = prevFlow.pendingOffer?.offerFields
            val storeHints = offerFields?.parsedOffer?.orders?.map { it.storeName } ?: emptyList()
            val offerHash = prevFlow.pendingOffer?.offerHash

            val jobId = UUID.randomUUID().toString()
            return region.copy(
                activeJob = Job(
                    jobId = jobId,
                    offerStoreHint = storeHints,
                    parentOfferHash = offerHash,
                    startedAt = obs.timestamp,
                ),
            )
        }

        // Job start: task flow without active job (recovery or mid-session restart)
        if (nextFlowVal.isTaskFlow() && region.activeJob == null) {
            val parsed = obs.parsed as? ParsedFields.TaskFields
            val jobId = UUID.randomUUID().toString()
            return region.copy(
                activeJob = Job(
                    jobId = jobId,
                    offerStoreHint = listOfNotNull(parsed?.storeName),
                    parentOfferHash = null,
                    startedAt = obs.timestamp,
                ),
            )
        }

        // Post-task: keep job alive through PostTask for payout capture
        // Job ends when we leave PostTask for non-task flow
        if (prevFlowVal == Flow.PostTask && !nextFlowVal.isTaskFlow() && nextFlowVal != Flow.PostTask && nextFlowVal != Flow.OfferPresented) {
            return completeActiveJob(region, obs)
        }

        return region
    }

    private fun updateTaskLifecycle(
        region: PlatformRegion,
        prevFlowVal: Flow,
        nextFlowVal: Flow,
        obs: Observation.FlowObservation,
    ): PlatformRegion {
        val parsed = obs.parsed

        // Task flow → create or update active task
        if (nextFlowVal.isTaskFlow()) {
            val taskPhase = nextFlowVal.toTaskPhase() ?: return region
            val taskSubFlow = nextFlowVal.toTaskSubFlow() ?: return region
            val taskFields = parsed as? ParsedFields.TaskFields

            val currentTask = region.activeTask
            val jobId = region.activeJob?.jobId ?: return region

            // Phase/subflow change → new task or update
            if (currentTask == null || currentTask.phase != taskPhase) {
                // New task (different phase or no active task)
                val completedTask = currentTask?.copy(completedAt = obs.timestamp)
                val recentTasks = if (completedTask != null) {
                    (region.recentTasks + completedTask).takeLast(MAX_RECENT_TASKS)
                } else region.recentTasks

                return region.copy(
                    activeTask = Task(
                        taskId = UUID.randomUUID().toString(),
                        jobId = jobId,
                        phase = taskPhase,
                        storeName = taskFields?.storeName ?: currentTask?.storeName,
                        customerNameHash = taskFields?.customerNameHash,
                        customerAddressHash = taskFields?.customerAddressHash,
                        deadlineMillis = taskFields?.deadline?.time,
                        activity = taskFields?.activity,
                        itemCount = taskFields?.itemCount,
                        redCardTotal = taskFields?.redCardTotal,
                        startedAt = obs.timestamp,
                        arrivedAt = if (taskSubFlow == TaskSubFlow.ARRIVED) obs.timestamp else null,
                    ),
                    recentTasks = recentTasks,
                )
            }

            // Same phase — update fields
            val justArrived = taskSubFlow == TaskSubFlow.ARRIVED && currentTask.arrivedAt == null
            return region.copy(
                activeTask = currentTask.copy(
                    storeName = taskFields?.storeName ?: currentTask.storeName,
                    customerNameHash = taskFields?.customerNameHash ?: currentTask.customerNameHash,
                    customerAddressHash = taskFields?.customerAddressHash ?: currentTask.customerAddressHash,
                    deadlineMillis = taskFields?.deadline?.time ?: currentTask.deadlineMillis,
                    activity = taskFields?.activity ?: currentTask.activity,
                    itemCount = taskFields?.itemCount ?: currentTask.itemCount,
                    redCardTotal = taskFields?.redCardTotal ?: currentTask.redCardTotal,
                    arrivedAt = if (justArrived) obs.timestamp else currentTask.arrivedAt,
                ),
            )
        }

        // PostTask → complete active task
        val postTask = region.activeTask
        if (nextFlowVal == Flow.PostTask && postTask != null) {
            val completedTask = postTask.copy(completedAt = obs.timestamp)
            return region.copy(
                activeTask = null,
                recentTasks = (region.recentTasks + completedTask).takeLast(MAX_RECENT_TASKS),
            )
        }

        // Leaving task/post-task flows to idle/awaiting → clear active task
        if (prevFlowVal.isTaskFlow() && !nextFlowVal.isTaskFlow() && nextFlowVal != Flow.PostTask) {
            val leavingTask = region.activeTask
            if (leavingTask != null) {
                val completedTask = leavingTask.copy(completedAt = obs.timestamp)
                return region.copy(
                    activeTask = null,
                    recentTasks = (region.recentTasks + completedTask).takeLast(MAX_RECENT_TASKS),
                )
            }
        }

        return region
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

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
            sessionGraceDeadline = null,
            idleEnteredAt = null,
            lastPostTaskPayHash = null,
            lastPostTaskFields = null,
        )
    }

    private fun completeActiveJob(region: PlatformRegion, obs: Observation): PlatformRegion {
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
