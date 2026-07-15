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
import cloud.trotter.dashbuddy.domain.state.PendingModeResume
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.SessionType
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
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

        // #762 D2: the accept-consumption grace moved to the per-platform SSOT
        // `GraceConfig.acceptGraceMs` (DoorDash 120s, Uber 600s) — read via
        // `TransitionPolicy.acceptGraceMs(platform)`, threaded to the offer lifecycle. The former
        // global `ACCEPT_GRACE_MS` const is deleted (Principle 8 — grace timing is per-platform).
    }

    /**
     * Deterministic entity-id mint (#344): derived only from replay-stable inputs
     * (platform, obs.timestamp, [PlatformRegion.mintCounter]) so crash-recovery
     * replay reproduces the live run's IDs. UUID.randomUUID broke effect
     * idempotency keys ("start_session:$sessionId") and diverged restored state
     * from already-persisted rows. Every mint MUST bump mintCounter in the same
     * copy() — use [offset] when minting more than one id per observation.
     */
    internal fun mintId(
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
    ): PlatformRegion = stampLastActedFlow(
        // #438 B3: the offer lifecycle runs FIRST, on THIS platform's owned pendingOffers, so an
        // accept-latched offer is marked accepted-pending-consumption before stepCore's task edge
        // consumes it (the old armAcceptStash mirror, run AFTER stepCore, is retired).
        reconcileDropoffStore(
            reconcileJobTasks(stepCore(stepOffers(prev, obs, policy), prevFlow, nextFlow, obs, policy)),
        ),
        obs,
    )

    /**
     * #438 item 5 (D3): record the last **non-null** own-platform flow this region stepped on
     * ([PlatformRegion.lastActedFlow]) so the per-region lifecycle edges diff against the flow THIS
     * platform acted on — not the shared global R0 flow. Stamped HERE in the [step] wrapper (not
     * inside [updateLifecycle]) so the SessionEnded / Offline early-returns can't skip it. A
     * non-FlowObservation (Timeout/UiInput/Loopback) or a flow-less FlowObservation (flow=null
     * clicks/notifications) leaves it unchanged — such a frame is not this platform acting on a
     * screen, and `nextFlow.flow` on it is whatever platform last owned R0.
     */
    private fun stampLastActedFlow(region: PlatformRegion, obs: Observation): PlatformRegion {
        val flow = (obs as? Observation.FlowObservation)?.flow ?: return region
        return if (region.lastActedFlow == flow) region else region.copy(lastActedFlow = flow)
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
                // #736 same-frame supersession: an incoming `task:unassigned` frame is the
                // authoritative abandon of the active task. Committing an overdue TASK_RETIRE here
                // FIRST would stamp `completedAt` on the (arrived) pickup — the seq-71 fabrication
                // hole — before `abandonActiveTask` runs on this same frame. Drop the retire instead;
                // the abandon below supersedes it (marks `unassignedAt`, leaves `completedAt` null).
                // SESSION_END (the graced-offline state) and any other kind still commit normally.
                val supersededByUnassign = pend.kind == DestructiveKind.TASK_RETIRE &&
                    (obs as? Observation.FlowObservation)?.flow == Flow.TaskUnassigned
                current = if (supersededByUnassign) {
                    current.copy(pendingDestructive = null)
                } else {
                    // Commit stamped at pend.since — the obs.timestamp of the signal
                    // that armed the grace — not the deadline: the dash/task really
                    // ended when the destructive signal appeared, the grace only
                    // delayed our belief in it (#431).
                    //
                    // #732: this pend.since stamp is what makes Task.completedAt (set below,
                    // in endSession/retireActiveTask) carry the grace-ARM time rather than
                    // commit time — the TRUE source of the sequenceId/occurredAt ordering
                    // invariant: a later PICKUP_CONFIRMED close-out sweep reads that
                    // completedAt as its OWN occurredAt (TaskEffects.kt), so THAT event can
                    // append to the log well AFTER intervening non-graced events while
                    // carrying an EARLIER domain timestamp than they do. Other event types
                    // (DASH_STOP, DELIVERY_COMPLETED) stamp their own occurredAt at
                    // commit-observation time and do NOT inherit this lag. See
                    // AppEventEntity's class KDoc ("sequenceId vs occurredAt") for the full
                    // invariant; this is a documented, accepted tradeoff (Option B), not a
                    // bug to silently "fix" by re-stamping here.
                    commitDestructive(current, pend.kind, pend.since)
                }
            }
        }

        // #605: lazy expiry of a graced screen-implied resume out of Paused. A
        // sustained online past the deadline (no intervening paused frame cancelled
        // it) commits the resume once — flip Paused→Online. Runs BEFORE the timeout
        // branch so the MODE_RESUME_COMMIT wake timer (a non-flow observation) can
        // commit an overdue resume, exactly like the destructive lazy expiry above.
        // Driven by obs.timestamp (never a wall clock) so crash-recovery replay
        // matches. Independent of pendingDestructive: during the field flap that slot
        // is BUSY holding the just-completed delivery's TASK_RETIRE grace.
        current.pendingModeResume?.let { pend ->
            if (obs.timestamp > pend.deadline) {
                current = commitModeResume(current, obs, policy)
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

            // Same mode — confirmed. A Paused-implying frame also CANCELS any armed
            // resume grace (#605): the modal is still up, the online flap was noise.
            impliedMode == current.mode -> current.copy(
                lastObservedAt = obs.timestamp,
                pendingModeResume = if (impliedMode == Mode.Paused) null else current.pendingModeResume,
            )

            // Click — records user intent, does NOT drive mode
            flowObs is Observation.Click -> current.copy(lastObservedAt = obs.timestamp)

            // #605: a screen-implied resume OUT of Paused is GRACED, not immediate.
            // DoorDash's pause sheet sits on the just-completed delivery summary, so
            // frames flap paused ↔ online; flipping on the first online frame re-mints
            // DASH_PAUSED and a spurious resume card on every edge. Arm a short pending
            // (keeping the original `since` so repeated online frames don't push the
            // deadline out) and STAY Paused: a paused frame cancels it (above), sustained
            // online past the deadline commits it (lazy expiry / MODE_RESUME_COMMIT timer),
            // and an OfferPresented screen — authoritative online evidence, structurally
            // absent from the flap — commits immediately (excluded here → falls to the
            // authoritative else). Screen-only: notifications carry no mode hints (verified),
            // and keeping the grace to the screen channel matches the observed defect.
            current.mode == Mode.Paused && impliedMode == Mode.Online &&
                obs is Observation.Screen && flowObs.flow != Flow.OfferPresented -> {
                val since = current.pendingModeResume?.since ?: obs.timestamp
                current.copy(
                    lastObservedAt = obs.timestamp,
                    pendingModeResume = PendingModeResume(
                        since = since,
                        deadline = since + policy.pauseResumeGraceMs(current.platform),
                    ),
                )
            }

            // Screen or Notification — authoritative, apply immediately
            else -> {
                val transitioned = applyModeTransition(current, impliedMode, obs, policy)
                // Heal lifecycle when coming Online from Offline (app restart
                // mid-task) — the live path (#715 struck the former Unexpected-
                // transition gate: no ruleset ever declared `outcomes`, so that arm
                // never fired). healActiveLifecycle self-guards: only acts if the
                // flow is a task flow with no active job, so it's safe to call broadly.
                if (current.mode == Mode.Offline && impliedMode == Mode.Online) {
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
        policy: TransitionPolicy,
    ): PlatformRegion {
        var region = prev.copy(
            mode = newMode,
            lastObservedAt = obs.timestamp,
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
                            deadline = obs.timestamp + policy.gracePeriodMs(region.platform),
                        ),
                    )
                }
            }
        }

        // #605: any COMMITTED mode transition out of Paused resolves a graced
        // screen-implied resume — clear the pending. Covers all three exits: a
        // sustained-online commit (Paused→Online), an instant OfferPresented commit
        // (Paused→Online), and the pause-safety timeout (Paused→Offline).
        if (prev.mode == Mode.Paused && newMode != Mode.Paused) {
            region = region.copy(pendingModeResume = null)
        }

        return region
    }

    /**
     * Commit a graced screen-implied resume out of [Mode.Paused] (#605) — the grace
     * lapsed sustained-online (lazy expiry / `MODE_RESUME_COMMIT` wake timer) or an
     * `OfferPresented` screen proved online. Mirrors [applyModeTransition]'s
     * Paused→Online path (same-session grace-resume vs. fresh-session logic); that
     * function also nulls [PendingModeResume] on any transition out of Paused.
     */
    private fun commitModeResume(
        region: PlatformRegion,
        obs: Observation,
        policy: TransitionPolicy,
    ): PlatformRegion =
        applyModeTransition(region, Mode.Online, obs, policy)

    /**
     * When the app comes Online from Offline (e.g., launched mid-pickup),
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
                    applyModeTransition(prev, Mode.Offline, obs, policy)
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
            val newDeadline = obs.timestamp + policy.authoritativeGraceMs(region.platform)
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
        // #438 item 5 (D3): the lifecycle edges below diff THIS region's own acted flow, not the
        // shared global R0 flow. `region.lastActedFlow` is still the pre-step value here (the stamp
        // runs in the [step] wrapper, after stepCore). Fallback to the global prev flow for legacy
        // snapshots (lastActedFlow=null) keeps single-platform behavior byte-identical — the sole
        // region acts on every own frame, so its lastActedFlow tracks R0.flow. A flow-less own obs
        // (flow=null) is not a flow edge (next=prev), never a diff against the other platform's
        // nextFlow.flow.
        val prev = region.lastActedFlow ?: prevFlow.flow
        val next = obs.flow ?: prev

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
        r = updateJobLifecycle(r, prev, next, obs, policy)

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
                // #630 R3: never let a COLLAPSED re-render (parsedPay == null) clobber an already-
                // captured EXPANDED receipt for the SAME announced task. A PostTask re-entry (e.g.
                // after a chained-offer decline) can render collapsed first, and if the retire-grace
                // close-out apportions off `lastPostTaskFields` before auto-expand restores the
                // itemized receipt, `apportion(null)` nulls every not-yet-minted drop's share while an
                // exit-minted drop kept its share → Σ < total. A DIFFERENT task's collapsed receipt is
                // a genuinely new receipt and still overwrites. `sessionEarnings` still folds below.
                // Accepted trade: on the same-task skip the collapsed frame's possibly-updated
                // `totalPay` is DISCARDED (the itemized expanded receipt is authoritative); an EXPANDED
                // re-render (`parsedPay != null`) is not a downgrade and refreshes both fields normally.
                val sameTaskCollapsedDowngrade = parsed.parsedPay == null &&
                    r.lastPostTaskFields?.parsedPay != null &&
                    postTaskTaskId != null &&
                    postTaskTaskId == r.lastAnnouncedPostTaskTaskId
                if (!sameTaskCollapsedDowngrade) {
                    r = r.copy(
                        lastPostTaskPayHash = payHash,
                        lastPostTaskFields = parsed,
                        lastAnnouncedPostTaskTaskId = postTaskTaskId ?: r.lastAnnouncedPostTaskTaskId,
                    )
                }
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
        obs: Observation.FlowObservation,
        policy: TransitionPolicy,
    ): PlatformRegion {
        // #438 B3: consume THIS region's own accepted-pending-consumption offer on a task flow — the
        // SINGLE mint source (richer than the old accept stash: the owned offer carries full fields +
        // evaluation). The offer was marked accepted-pending-consumption by [stepOffers] when the own
        // flow left offer-presentation with the accept latch set. This one path covers:
        //   - the happy path (offer→task in one step: the survivor is marked AND consumed same-frame);
        //   - the F3 teardown race (the survivor was marked on a prior leave-edge — a
        //     `waiting_for_offer` frame that popped presentation — and is consumed when the task flow
        //     finally arrives).
        // consumeAcceptIntoJob mints fresh / appends an add-on / #596-T2 closes+mints. The trigger is
        // structurally per-region now (the offer lives on this region), so the interim
        // offerBelongsToRegion cross-region guard is gone.
        var current = region
        val accepted = current.pendingOffers.lastOrNull { it.acceptedAt != null }
        if (nextFlowVal.isTaskFlow() && accepted != null) {
            if (!isOfferAcceptExpired(accepted, obs, policy.acceptGraceMs(current.platform))) {
                val consumed = current.copy(pendingOffers = current.pendingOffers.filterNot { it === accepted })
                return consumeAcceptIntoJob(consumed, obs, acceptInputsFromPending(accepted, accepted.acceptedAt))
            }
            // A lapsed survivor is a corpse — clear it INLINE but do NOT early-return; the frame must
            // still process normally (a lost arrivedAt stamp would trip the #615 arrival gate and the
            // job would never close). Fall through with the corpse cleared.
            current = current.copy(pendingOffers = current.pendingOffers.filterNot { it === accepted })
        }

        // Job start: task flow without active job (recovery, mid-session restart, or an accept that
        // was never latched — genuine bare fallback with no economics).
        if (nextFlowVal.isTaskFlow() && current.activeJob == null) {
            val parsed = obs.parsed as? ParsedFields.TaskFields
            return current.copy(
                activeJob = Job(
                    jobId = mintId("job", current, obs),
                    offerStoreHint = listOfNotNull(parsed?.storeName),
                    parentOfferHash = null,
                    startedAt = obs.timestamp,
                ),
                mintCounter = current.mintCounter + 1,
            )
        }

        // Post-task: keep job alive through PostTask for payout capture
        // Job ends when we leave PostTask for non-task flow
        //
        // #762 D2 accepted residual (adversarial finding 2): on a coarse-only trip a marker-less
        // `task:active` frame BETWEEN post-trip and idle walks the acted flow PostTask → TaskActive
        // → Idle, so this edge never fires (the intermediate next IS a task flow; by the idle frame
        // prev is TaskActive) — and a coarse trip has no activeTask, so no TASK_RETIRE close-out
        // either: the job stays open until session end or the next accept's #596 T2 close+mint.
        // Deliberately NOT closed with a grace here: zero Uber corpus to validate the shape, a
        // wrong close on a stacked job is fabrication, and an open job fails toward absorption —
        // the preferred failure direction. See ADR-0002 amendment 2026-07-15 (residual).
        if (prevFlowVal == Flow.PostTask && !nextFlowVal.isTaskFlow() && nextFlowVal != Flow.PostTask && nextFlowVal != Flow.OfferPresented) {
            return completeActiveJob(current)
        }

        return current
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
     *
     * #596 T1: a retire that leaves the job *physically complete* (every dropoff
     * delivered, nothing outstanding) also closes the job — DoorDash routinely
     * skips the post-delivery receipt (the pre-#596 machine's only job-exit), so
     * without this the job never closes and later independent offers get absorbed
     * into it. Gated on provenance: a retire armed by deliberating on a mid-route
     * add-on offer ([Flow.OfferPresented]) does NOT close (that drop isn't
     * delivered — the accept is an add-on).
     */
    private fun retireActiveTask(region: PlatformRegion, timestamp: Long): PlatformRegion {
        val armedFromFlow = region.pendingDestructive
            ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }?.armedFromFlow
        val completed = region.activeTask?.copy(completedAt = timestamp)
            ?: return region.copy(pendingDestructive = null)
        val recentTasks = (region.recentTasks + completed).takeLast(MAX_RECENT_TASKS)
        val retired = region.copy(
            activeTask = null,
            recentTasks = recentTasks,
            pendingDestructive = null,
        )
        val job = retired.activeJob
        return if (job != null && armedFromFlow != Flow.OfferPresented &&
            isJobPhysicallyComplete(job, recentTasks, justRetired = completed)
        ) {
            completeActiveJob(retired)
        } else {
            retired
        }
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
            // #438 B3: the session's over — pending/accepted offers must not leak into the next dash.
            pendingOffers = emptyList(),
        )
    }

    internal fun completeActiveJob(region: PlatformRegion): PlatformRegion {
        val job = region.activeJob ?: return region
        return region.copy(
            activeJob = null,
            lastPostTaskPayHash = null,
            lastPostTaskFields = null,
        )
    }
}

// #596/#749: `isJobPhysicallyComplete` (the strict arm + the per-customer coverage arm) lives in
// `JobCompleteness.kt` — moved out of this file because it grew past the #237 size ceiling. Both
// call sites (T1 `retireActiveTask`, T2 `consumeAcceptIntoJob`) call the same `internal` predicate.

// =========================================================================
// FLOW EXTENSION HELPERS
// =========================================================================

// #762 D2: [Flow.TaskActive] IS a task flow (so an accept consumes into a job) but is DELIBERATELY
// phase-less — [toTaskPhase]/[toTaskSubFlow] return null for it. Every phase/subflow consumer
// already guards `toTaskPhase() ?: return`/`toTaskSubFlow() ?: return`, so a `task:active`
// observation never mints, displaces, or resumes a task (verified: TaskLifecycle.kt:52-53 and
// healActiveLifecycle both early-return on the null phase). Retire semantics, precisely: a
// `task:active` frame BETWEEN phased task flows is not a "left the task family" edge (arms
// nothing), and an interposed `task:active` frame neither cancels nor early-commits a pending
// TASK_RETIRE (the null-phase early-return happens before the same-phase update's grace clear) —
// but leaving `task:active` TO a non-task flow (e.g. idle) still arms the normal retire grace,
// exactly as leaving any task flow does. See the enum KDoc + ADR-0002 amendment 2026-07-15.
internal fun Flow.isTaskFlow(): Boolean = this in setOf(
    Flow.TaskPickupNavigation,
    Flow.TaskPickupArrived,
    Flow.TaskDropoffNavigation,
    Flow.TaskDropoffArrived,
    Flow.TaskActive,
)

internal fun Flow.toTaskPhase(): TaskPhase? = when (this) {
    Flow.TaskPickupNavigation, Flow.TaskPickupArrived -> TaskPhase.PICKUP
    Flow.TaskDropoffNavigation, Flow.TaskDropoffArrived -> TaskPhase.DROPOFF
    // TaskActive is phase-less by design → null (structurally inert to task lineage).
    else -> null
}

internal fun Flow.toTaskSubFlow(): TaskSubFlow? = when (this) {
    Flow.TaskPickupNavigation, Flow.TaskDropoffNavigation -> TaskSubFlow.NAVIGATION
    Flow.TaskPickupArrived, Flow.TaskDropoffArrived -> TaskSubFlow.ARRIVED
    else -> null
}
