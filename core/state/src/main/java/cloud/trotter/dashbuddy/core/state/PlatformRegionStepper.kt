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
import cloud.trotter.dashbuddy.domain.state.PendingSessionPay
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
        // commit an overdue resume, exactly like the destructive lazy expiry above. (The
        // #1029 SESSION_PAY_SETTLE timer below is handled the same way — by lazy expiry, so
        // `handleTimeout`'s `else -> prev` is the correct and complete handling for both.)
        // Driven by obs.timestamp (never a wall clock) so crash-recovery replay
        // matches. Independent of pendingDestructive: during the field flap that slot
        // is BUSY holding the just-completed delivery's TASK_RETIRE grace.
        current.pendingModeResume?.let { pend ->
            if (obs.timestamp > pend.deadline) {
                current = commitModeResume(current, obs, policy)
            }
        }

        // #1029: lazy expiry of a parked dash running-total read. It commits once it has stood
        // UNCHALLENGED for the settle window — a different read would have replaced it (with a
        // fresh deadline) and a read equal to the committed figure would have cleared it, so
        // reaching the deadline is itself the evidence that the wheel settled here. Runs BEFORE
        // the timeout branch so the SESSION_PAY_SETTLE wake timer can land it (see
        // [PlatformRegion.pendingSessionPay] for why a timer is load-bearing here and repetition
        // is not a usable signal). Driven by obs.timestamp (never a wall clock) so crash-recovery
        // replay matches.
        //
        // `>=`, not `>`: the wake timer's duration is EXACTLY `deadline - obs.timestamp` and the
        // fired observation is stamped with the wall clock, so a fire landing on the deadline (or
        // after a clock step-back) would be a no-op — and no frame is coming to retry, by the very
        // FrameGate argument that made the timer necessary. The park would be stranded.
        // (`pendingModeResume` above keeps `>`: a resume grace is re-driven by ordinary frames.)
        current.pendingSessionPay?.let { pend ->
            // #1029: a park is OWNED by (flow, PLATFORM) — the R0 surface the read was made on AND
            // the platform that put that surface on screen. `FlowRegionStepper` stamps
            // `activePlatform` on every flow-bearing frame and leaves R0 untouched on
            // Timeout/UiInput/Loopback, so R0 carries exactly the ownership fact needed. Compared
            // as `Platform` values — never a literal (principle 8).
            //
            // #1052: ownership is checked on BOTH sides of this observation. `ownedAfter` alone
            // (the #1029 shape) is blind to a departure this region was never stepped for:
            // `stepPlatforms` steps only `obs.platform`'s region, so an Uber frame moves the
            // SHARED R0 without ever reaching the DoorDash region that holds the park — and when
            // the owner returns, R0 reads owned again and the ORIGINAL deadline commits a figure
            // that was off screen for the whole interlude. `ownedBefore` is what records that
            // absence: R0 as it was BEFORE this observation is the only surviving evidence of it.
            //
            // Three-way, in order:
            //  1. `!ownedBefore` → the surface departed while this region was not being stepped.
            //     DROP, whatever this observation is; a returning frame re-parks with a FRESH
            //     deadline through [settleSessionPay], which is the honest window for a read whose
            //     surface has just come back.
            //  2. a flow-LESS observation (a timer, a click, a loopback, a flow-less
            //     notification) that no longer owns R0 → DROP, skipping the expiry: such a frame
            //     cannot itself be the departure, so the departure already happened. (Today this
            //     is unreachable behind rule 1 — a flow-less observation leaves R0 untouched, so
            //     `ownedAfter == ownedBefore` — and it is stated anyway so the rule is complete on
            //     its own terms rather than resting on that invariant holding elsewhere forever.)
            //  3. otherwise expiry as normal, THEN `!ownedAfter` → drop.
            val ownedBefore = prevFlow.flow == pend.flow && prevFlow.activePlatform == current.platform
            val ownedAfter = nextFlow.flow == pend.flow && nextFlow.activePlatform == current.platform
            val flowBearing = (obs as? Observation.FlowObservation)?.flow != null
            if (!ownedBefore || (!flowBearing && !ownedAfter)) {
                current = current.copy(pendingSessionPay = null)
            } else {
                if (obs.timestamp >= pend.deadline) {
                    // A CONTRADICTING read on the very frame the park would commit on supersedes
                    // it: a late timer and a fresh idle frame can collide, and committing the stale
                    // park first and then re-parking the fresh read for another whole window shows
                    // the dasher a figure that the same frame already disproved.
                    val read = obs.sessionPayRead()
                    current = if (read != null && !centsEqual(read, pend.value)) {
                        current.copy(pendingSessionPay = null)
                    } else {
                        commitSessionPay(current, pend)
                    }
                }

                // The park is FLOW-SCOPED — a read is evidence only while the surface it came from
                // is on screen. Placed immediately AFTER the expiry so a park that stood its whole
                // window on its own surface still commits on the departure frame; anything younger
                // is dropped (the diff emits the CancelTimeout). See [PendingSessionPay.flow].
                if (!ownedAfter) current = current.copy(pendingSessionPay = null)
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

        // #967: stamp the opportunistic ratings snapshot BEFORE the lifecycle gates.
        // updateLifecycle early-returns on `mode == Offline` (idle-anchor/pending clear), and the
        // ratings stamp used to sit AFTER that return — so a ratings observation while the platform
        // was Offline (precisely when a dasher browses their ratings) was parsed and then discarded
        // (fielded 2026-07-30, minutes after #963 shipped: a fully-parsed hub frame left the prior
        // all-null snapshot in place; it had only ever stamped mid-dash). Ratings stamping is
        // observation-driven and mode-independent (#962: opportunistic, display-only — the
        // RatingsSnapshotIsDisplayOnlyTest allowlist covers this site), so it rides ahead of the
        // lifecycle, not inside it.
        val afterRatings = updateRatings(afterMode, flowObs)

        // Update session/job/task lifecycle based on flow changes
        return updateLifecycle(afterRatings, prevFlow, nextFlow, flowObs, policy)
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
                        // #1029: a running-total read parked before this dash existed
                        // describes the previous one — never settle it against this session.
                        pendingSessionPay = null,
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

        // #1052: leaving Online DROPS a parked running-total read. A paused or offline dash cannot
        // change its running total, so the committed figure stands and an un-settled read has
        // nothing left that could confirm it. The flow-scoped drop does not cover this: the pill's
        // SURFACE is gone even though R0 may still read the park's flow — `dash_paused` declares
        // `modeHint: paused` and NO flow, and the offline map keeps `Idle` — so ownership stays
        // "valid" while the only thing that could contradict the park is off screen. Written HERE
        // because [applyModeTransition] is the single site that moves `mode` (the pause-safety
        // timeout and the graced resume commit both route through it). Fail-null (#745).
        if (newMode != Mode.Online) {
            region = region.copy(pendingSessionPay = null)
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

        // Update session fields from observations. `next` is the flow the read is being made
        // ON — the settle gate parks it with that flow (#1029, see [PendingSessionPay.flow]).
        r = updateSessionFields(r, obs, next, policy)

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
                    // #1029: this is a NON-gated write of runningEarnings, so it supersedes every
                    // park older than itself — otherwise a park made before the receipt could
                    // expire afterwards and overwrite it. `since >= now` is load-bearing: the
                    // receipt's OWN wheel read was parked by updateSessionFields a few lines
                    // above, on THIS same frame, and that park must survive.
                    r = r.supersedeParksOlderThan(obs.timestamp)
                }
            }
        }

        // (Ratings stamping moved to stepCore, ahead of this function's early returns — #967.)

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

    private fun updateSessionFields(
        region: PlatformRegion,
        obs: Observation.FlowObservation,
        flow: Flow,
        policy: TransitionPolicy,
    ): PlatformRegion {
        val parsed = obs.parsed
        var r = region

        when (parsed) {
            is ParsedFields.IdleFields -> {
                if (parsed.zoneName != null) r = r.copy(zoneName = parsed.zoneName)
                if (parsed.sessionType != null) r = r.copy(sessionType = parsed.sessionType)
                r.session?.let { session ->
                    val pay = parsed.sessionPay
                    if (pay != null) r = settleSessionPay(r, session, pay, obs.timestamp, flow, policy)
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
                        // #1029: the receipt's "This dash so far" is the SAME digit-wheel the
                        // on-dash pill renders — `dropoff.json5` reads it through
                        // `parseGlyphCurrency` on both summary rules — so it takes the SAME settle
                        // gate. Exempting it would leave the identical well-formed-mid-spin hole
                        // open on the surface that closes a delivery (the PR's own golden had
                        // approved a pre-roll $17.75 against a $35.47 dash one second later).
                        r = settleSessionPay(r, session, earnings, obs.timestamp, flow, policy)
                    }
                }
            }
            is ParsedFields.SessionEndedFields -> {
                // #1030: only a REAL parse moves `runningEarnings` — a missed total is null now,
                // and writing it through would zero a figure the summary never contradicted.
                val total = parsed.totalEarnings
                if (total != null) {
                    r.session?.let { session ->
                        // #1029: a non-gated write, so it supersedes every park older than itself
                        // (same rule as the PostTask-entry accumulation above). This arm is
                        // UNREACHABLE on the fielded path — `updateLifecycle` returns early on
                        // `Flow.SessionEnded` with a live session, to arm the authoritative
                        // SESSION_END grace — so what actually closes the case there is
                        // `Observation.sessionPayRead()` treating the summary's total as a
                        // contradicting read at expiry time. The call stays here because this is
                        // still the honest place for the invariant if that early return ever moves:
                        // "no direct writer leaves an older park alive" is stated at every writer,
                        // not wherever it happens to be reachable today.
                        r = r.copy(session = session.copy(runningEarnings = total))
                            .supersedeParksOlderThan(obs.timestamp)
                    }
                }
            }
            is ParsedFields.PausedFields -> {
                // Pause fields are handled by mode inference, not session
            }
            else -> { /* no session updates */ }
        }

        return r
    }

    /**
     * The **settle gate** for a parsed dash running total (#1029): a value commits to
     * [Session.runningEarnings] only once it has stood **unchallenged for the settle window**
     * ([TransitionPolicy.sessionPaySettleMs]).
     *
     * The platform now renders that total as an animated digit-wheel, so a capture can catch it
     * mid-spin. `parseGlyphCurrency` rejects the malformed intermediates at the parse layer, but a
     * spin value that happens to be well-FORMED ($470.00 on a $16.70 dash) is indistinguishable
     * from a real figure by inspection — only by TIME. Hence:
     *
     *  - read == 0.00 with a positive committed total → the LOAD PLACEHOLDER; ignore it entirely
     *                        (the same earnings-pill component renders `$0.00` for seconds before
     *                        the figure loads — fielded 08-23 15:53:42 `$0.00` → `$61.80` at :48 —
     *                        and a dash running total never legitimately returns to zero mid-dash).
     *                        Deliberately NOT a general monotonic guard: a genuine downward
     *                        correction is out of scope, only the zero placeholder is refused.
     *  - read == committed → the wheel is at rest; drop any park and change nothing.
     *  - read == parked    → the same value again; keep the park AND its ORIGINAL deadline (an
     *                        intervening screen and a return must not extend the window).
     *  - otherwise         → a new sighting; REPLACE the park with a fresh deadline, stamped with
     *                        the [Flow] it was read on (the park dies when that surface leaves).
     *
     * Both equality tests are CENT-tolerant: `runningEarnings` can hold an accumulated sum
     * (`accumulatedDeliveryPay + totalPay`) that is not bit-equal to the 2-dp figure the wheel
     * renders, and an exact `Double` compare there would needlessly re-park and re-arm.
     *
     * The commit itself is [commitSessionPay], run by lazy expiry in [stepCore] on the first
     * observation AT or past the deadline — a `SESSION_PAY_SETTLE` wake timer guarantees one
     * arrives, and that timer is load-bearing rather than punctual (see
     * [PlatformRegion.pendingSessionPay] for why repetition is not a signal this reducer can ever
     * observe).
     *
     * Split immediate/gated fields: only the running total is gated here. The other [IdleFields]
     * (`zoneName`, `sessionType`) are written immediately — they are not wheel-rendered and carry
     * no mid-animation failure mode. BOTH running-total feeds go through this one function: the
     * idle earnings pill and the receipt's own "This dash so far" wheel.
     *
     * Pure, platform-agnostic (state keyed by this region only, no [Platform] branch, no wall
     * clock — the deadline is derived from `obs.timestamp`). See
     * [PlatformRegion.pendingSessionPay].
     */
    private fun settleSessionPay(
        region: PlatformRegion,
        session: Session,
        pay: Double,
        now: Long,
        flow: Flow,
        policy: TransitionPolicy,
    ): PlatformRegion = when {
        pay == 0.0 && session.runningEarnings > 0.0 -> region
        centsEqual(pay, session.runningEarnings) -> region.copy(pendingSessionPay = null)
        region.pendingSessionPay?.let { centsEqual(pay, it.value) } == true -> region
        else -> region.copy(
            pendingSessionPay = PendingSessionPay(
                value = pay,
                since = now,
                deadline = now + policy.sessionPaySettleMs(region.platform),
                flow = flow,
            ),
        )
    }

    /**
     * Two money figures that describe the same total (#1029). `Session.runningEarnings` can hold an
     * ACCUMULATED sum, which is not bit-equal to the 2-dp figure the platform renders for it, so an
     * exact `Double` compare would treat a settled read as "new" and re-park it forever.
     */
    private fun centsEqual(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 0.005

    /**
     * The parsed dash running total this observation carries, if any (#1029) — every surface that
     * states the figure, in ONE place, so the expiry's contradiction check and the gate itself can
     * never disagree about what counts as a running-total read.
     *
     * The first two are the gated feeds (the on-dash pill and the receipt's wheel). The THIRD, the
     * dash summary's `totalEarnings`, never parks — it is an authoritative direct write — but it is
     * emphatically a running-total READ, and on the fielded path it is the ONLY thing that can
     * contradict a park at the moment the park expires: `updateLifecycle` returns early on
     * `Flow.SessionEnded` with a live session (it arms the authoritative SESSION_END grace there),
     * so the [ParsedFields.SessionEndedFields] arm of [updateSessionFields] is unreachable on that
     * path and its `supersedeParksOlderThan` never runs. Without this arm a $470 mid-spin park
     * parked before "End Dash" would COMMIT on the summary frame and ride into the #596 close-out
     * sweep's `DELIVERY_COMPLETED.sessionEarnings` and the HUD latch.
     */
    private fun Observation.sessionPayRead(): Double? =
        when (val parsed = (this as? Observation.FlowObservation)?.parsed) {
            is ParsedFields.IdleFields -> parsed.sessionPay
            is ParsedFields.PostTaskFields -> parsed.sessionEarnings
            is ParsedFields.SessionEndedFields -> parsed.totalEarnings
            else -> null
        }

    /**
     * Drop a parked running-total read that is OLDER than a direct write of
     * [Session.runningEarnings] (#1029). Every writer that bypasses the settle gate calls this: a
     * park made before that write describes staler evidence, and letting it expire afterwards
     * would silently overwrite the newer figure. `since >= now` keeps a park made on the SAME
     * frame (the receipt's own wheel read, parked microseconds earlier in [updateSessionFields]).
     */
    private fun PlatformRegion.supersedeParksOlderThan(now: Long): PlatformRegion =
        copy(pendingSessionPay = pendingSessionPay?.takeIf { it.since >= now })

    /**
     * Commit a parked running-total read whose settle window lapsed (#1029). With no session to
     * describe the park is simply DROPPED — a figure parked against a dash that has since ended is
     * never invented into a later one.
     */
    private fun commitSessionPay(
        region: PlatformRegion,
        pend: PendingSessionPay,
    ): PlatformRegion {
        val session = region.session ?: return region.copy(pendingSessionPay = null)
        return region.copy(
            session = session.copy(runningEarnings = pend.value),
            pendingSessionPay = null,
        )
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
                overallRatingPoints = parsed.overallRatingPoints,
                tierLabel = parsed.tierLabel,
                qualityRate = parsed.qualityRate,
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
        // ONE spelling of the inline-committed retire copy (#997 amendment) — see [completedInline].
        val completed = region.activeTask?.completedInline(timestamp)
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
            // #1029: an un-settled running-total read describes the dash that just ended.
            pendingSessionPay = null,
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
