package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.model.ratings.RatingsSnapshot
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.ClosedJobReceipt
import cloud.trotter.log.gig.domain.state.JobReceiptAnchors
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.IdleSurface
import cloud.trotter.log.gig.domain.state.AcceptedOfferEconomics
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingDestructive
import cloud.trotter.log.gig.domain.state.PendingModeResume
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.PendingWake
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.mintWakeId
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.SessionType
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskIntent
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.TaskSubFlow
import timber.log.Timber
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

        /**
         * #1078 — the `TASK_RETIRE` provenances ([PendingDestructive.armedFromFlow], #596) a dash end
         * may NOT absorb as evidence of a finished delivery. See [absorbableRetire]:
         * `OfferPresented` retires an UNDELIVERED drop (the dasher stepped off to deliberate on an
         * add-on), and a `PostTask` retire's completion is already minted on the receipt's exit
         * frame. A null provenance is refused separately — unprovenanced evidence is not evidence.
         * Flow VALUES, never platform literals (principle 8).
         */
        private val UNABSORBABLE_RETIRE_FLOWS = setOf(Flow.OfferPresented, Flow.PostTask)
    }

    /**
     * Deterministic entity-id mint (#344): derived only from replay-stable inputs
     * (platform, [timestamp], [PlatformRegion.mintCounter]) so crash-recovery
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
        timestamp: Long = obs.timestamp,
    ): String = "$kind-${region.platform.wire}-$timestamp-${region.mintCounter + offset}"


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
            reconcileJobTasks(
                // #1033 review R1: the re-price window closes the moment the NEXT job is minted —
                // run this where `activeJob` is final for the step (nothing after stepCore changes
                // whether a job is active).
                closeReceiptRepriceWindow(
                    stepCore(
                        // #1073 round 13: the PostTask-exit latch is a pure flow-edge fact about the
                        // frame, so it is stamped before any of stepCore's early returns can skip it.
                        stampPostTaskExit(
                            // #1033 round 4: the re-price handoff lives exactly ONE transition.
                            // Clearing it at the very top means the value `EffectMap` sees is always
                            // the one THIS step decided — and a stale one restored from a snapshot is
                            // dropped before it can be re-emitted.
                            landArrivalEstimate(stepOffers(prev.copy(pendingReceiptReprice = null), obs, policy), obs),
                            prevFlow, obs,
                        ),
                        prevFlow, nextFlow, obs, policy,
                    ),
                ),
            ).let { requestArrivalEstimate(it, obs) },
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

    /**
     * #1033 round 10 — latch that the completion mint has RUN for this job: this region's own acted
     * flow left `PostTask` at least once. A structural flow fact only (round 8's lesson: never a
     * claim about what a completion carried); the terminal teardown uses it to tell a drop that
     * already has a row from one the bail is force-completing right now.
     *
     * Stamped HERE in the [step] wrapper, for the same reason [stampLastActedFlow] is (#1073): no
     * early return in [stepCore] or `updateLifecycle` — the `SessionEnded` arm, the `Offline` arm,
     * the timeout branch — can skip it. `EffectMap.diffDeliveryCompletion` diffs exactly this edge
     * off `lastActedFlow`, which the wrapper also owns, so the emitter and the latch cannot
     * disagree about what a PostTask exit is. Applied BEFORE [stepCore] so a teardown committed by
     * this same frame's lazy expiry already sees it.
     */
    private fun stampPostTaskExit(
        region: PlatformRegion,
        prevFlow: FlowRegion,
        obs: Observation,
    ): PlatformRegion {
        val (prev, next) = actedFlowEdge(region, prevFlow, obs)
        if (prev != Flow.PostTask || next == Flow.PostTask) return region
        val anchors = region.jobReceiptAnchors ?: return region
        // #1078 round 4: record WHICH task this exit mints, not merely that an exit happened. The
        // job-wide `exitedPostTask` flag cannot answer a per-drop question — in a stacked job D1's
        // exit latches it and D2's receipt then moves the announce id — so the per-task set is
        // written from [exitMintCandidate], the emitter's OWN eligibility, on the SAME acted-flow
        // edge (`actedFlowEdge`) the emitter diffs. One decision, two readers.
        //
        // The pre-step region is passed for both sides: the emitter's `next` only resolves the
        // subject id to the freshest copy of the task, and the TASK ID is all this records.
        // Deliberately NOT short-circuited on `anchors.exitedPostTask` any more — a second drop's
        // exit must still add ITS id to the set.
        val mintedId = exitMintCandidate(region, region, prev, next)?.taskId
        val updated = anchors.copy(
            exitedPostTask = true,
            exitMintedTaskIds = if (mintedId != null) {
                anchors.exitMintedTaskIds + mintedId
            } else {
                anchors.exitMintedTaskIds
            },
        )
        return if (updated == anchors) region else region.copy(jobReceiptAnchors = updated)
    }

    private fun stepCore(
        prev: PlatformRegion,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation,
        policy: TransitionPolicy,
    ): PlatformRegion {
        var current = prev
        val flowObs = obs as? Observation.FlowObservation
        // Resolve once before lazy expiry so this frame's evidence also governs a pending resume.
        val impliedMode = flowObs?.let { policy.resolveMode(it.flow, it.modeHint) }

        // Lazy expiry: a pending destructive transition (dash-end / task-retire / job-abandon)
        // whose deadline has passed is confirmed — committed now. Driven by
        // obs.timestamp (never a wall clock) so crash-recovery replay matches.
        // Runs BEFORE the timeout branch: a routed timeout (#342) is exactly the
        // kind of non-flow observation that must be able to commit an overdue
        // provisional transition.
        // Lapse is [graceLapsed] (#1054): strictly past for a frame, or this pending's OWN wake
        // (identified by its `GraceWake` payload) whenever it lands. See `GraceExpiry.kt`.
        current.pendingDestructive?.let { pend ->
            if (pend.kind == DestructiveKind.JOB_ABANDON) {
                // Any task frame restores absorption, even without parsed task identity. PostTask
                // hands ownership to #596. Check before expiry so neither can abandon a live job.
                if (flowObs?.flow?.isTaskFlow() == true || flowObs?.flow == Flow.PostTask) {
                    current = current.copy(pendingDestructive = null)
                    return@let
                }
                // An offer overlay supplies no abandonment evidence, even past the deadline.
                // Only this pending's wake or a later non-task frame may commit it.
                if (flowObs?.flow == Flow.OfferPresented ||
                    (flowObs == null && !obs.isWakeFor(TimeoutType.GRACE_COMMIT, pend.wakeId))
                ) return@let
            }
            if (graceLapsed(pend.deadline, pend.wakeId, obs, TimeoutType.GRACE_COMMIT)) {
                // #736 same-frame supersession: an incoming `task:unassigned` frame is the
                // authoritative abandon of the active task. Committing an overdue TASK_RETIRE here
                // FIRST would stamp `completedAt` on the (arrived) pickup — the seq-71 fabrication
                // hole — before `abandonActiveTask` runs on this same frame. Drop the retire instead;
                // the abandon below supersedes it (marks `unassignedAt`, leaves `completedAt` null).
                //
                // #1078 round 2 extends the SAME rule to an ABSORBED retire. A `SESSION_END`
                // carrying `absorbedRetireSince` is the retire's evidence parked in another slot,
                // and it must not outlive the retire it stands for: an abandon landing on the commit
                // frame is the platform saying that order was never delivered. The dash really IS
                // over, so the end still commits — but DISOWNED, in two inseparable halves:
                //
                //  1. `absorbedRetireSince` is cleared, so `endSession` force-stamps instead of
                //     honoring (the shape the amdt-#5 T3 guard exists to refuse), and
                //  2. the task is marked `unassignedAt` at this frame.
                //
                // Half 2 is what makes the refusal BITE. `EffectMap` judges the mint against the
                // PRE-step region, whose pending still carries the absorbed value — so clearing it
                // inside the step is invisible to [retirePendingForMint] and the close-out sweep
                // would mint the fabricated completion anyway. The `unassignedAt` marker is read off
                // the task itself and is answered by firewalls that already exist: the close-out
                // loop's own `unassignedAt` skip (#736/#752), `isJobPhysicallyComplete`'s exclusion,
                // and `detectAcceptMismatch` counting the order as ACCOUNTED — which is the honest
                // answer, because it was abandoned, not stranded. It is also the truthful mark: the
                // frame IS the platform stating the dasher unassigned this order — and `TaskEffects`
                // detects that marker and emits the proper `TASK_UNASSIGNED`, attributed through
                // [closingSession] to the session that just ended (round 3; the round-2 note here
                // claiming no event fires was wrong).
                //
                // The same disown runs on an IN-WINDOW unassign frame — see [disownAbsorbedRetire],
                // the one owner of both halves.
                //
                // Every other kind and provenance commits normally.
                val unassignFrame = (obs as? Observation.FlowObservation)?.flow == Flow.TaskUnassigned
                val supersededByUnassign = pend.kind == DestructiveKind.TASK_RETIRE && unassignFrame
                val disownedByUnassign = pend.kind == DestructiveKind.SESSION_END &&
                    pend.absorbedRetireSince != null && unassignFrame
                current = if (supersededByUnassign) {
                    current.copy(pendingDestructive = null)
                } else if (disownedByUnassign) {
                    commitDestructive(
                        disownAbsorbedRetire(current, obs.timestamp),
                        pend.kind, pend.since, obs.timestamp,
                    )
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
                    commitDestructive(current, pend.kind, pend.since, obs.timestamp)
                }
            }
        }

        // #605/#1311: lazy expiry of a screen-implied resume from Paused or post-end Offline.
        // Sustained online past the deadline (no confirming Paused/Offline frame cancelled
        // it) commits the resume once. Runs BEFORE the timeout
        // branch so the MODE_RESUME_COMMIT wake timer (a non-flow observation) can
        // commit an overdue resume, exactly like the destructive lazy expiry above. (The
        // #1029 SESSION_PAY_SETTLE timer below is handled the same way — by lazy expiry, so
        // `handleTimeout`'s `else -> prev` is the correct and complete handling for both.)
        // Driven by obs.timestamp (never a wall clock) so crash-recovery replay
        // matches. Independent of pendingDestructive: during the field flap that slot
        // is BUSY holding the just-completed delivery's TASK_RETIRE grace.
        // [graceLapsed] (#1054), as above.
        current.pendingModeResume?.let { pend ->
            if (graceLapsed(pend.deadline, pend.wakeId, obs, TimeoutType.MODE_RESUME_COMMIT)) {
                // #1311: end at 0 s, hint at 6 s, late wake, Offline at 14.001 s must CANCEL,
                // not mint a session before the same frame goes Offline. Paused also contradicts
                // either resume origin (#605); let the normal mode step apply that frame once.
                current = when {
                    impliedMode == Mode.Offline || impliedMode == Mode.Paused ->
                        current.copy(pendingModeResume = null)
                    obs is Observation.Timeout || flowObs != null -> commitModeResume(current, obs, policy)
                    else -> current
                }
            }
        }

        current = SessionPaySettleGate.lapse(current, prevFlow, nextFlow, obs)

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
                    current = endSession(current, pend.since, obs.timestamp)
                }
            }
        }

        if (flowObs == null) return current

        // #1315: explicit completion-steps intent is a second delivery witness. The step wrapper's
        // reconcileJobTasks mirrors this copy into Job.tasks; arrival/dwell and flow stay untouched.
        // Accepted residual: a delayed click stamps whichever drop is active. Its own screenIs gate
        // bounds it to a drop-off surface; the stacked-drop guard prevents migration to a sibling.
        // Navigation to a different screen on the same drop clears the witness (a refused or abandoned tap).
        val task = current.activeTask
        if (obs is Observation.Click && obs.platform == current.platform &&
            (obs.parsed as? ParsedFields.ClickFields)?.intent == TaskIntent.COMPLETE_DELIVERY &&
            task?.phase == TaskPhase.DROPOFF && task.completionStepsAt == null && task.completedAt == null
        ) {
            current = current.copy(activeTask = task.copy(
                completionStepsAt = obs.timestamp, completionStepsScreen = obs.screenRuleId,
            ))
        }

        val afterMode = when {
            // No mode signal
            impliedMode == null -> current.copy(lastObservedAt = obs.timestamp)

            // Same mode — confirmed. Paused/Offline cancels a pending resume (#605/#1311):
            // the Online hint was noise.
            impliedMode == current.mode -> current.copy(
                lastObservedAt = obs.timestamp,
                pendingModeResume = if (impliedMode == Mode.Paused || impliedMode == Mode.Offline) {
                    null
                } else current.pendingModeResume,
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
                armModeResume(current, obs, policy)
            }

            // #1311: the dash-controls sheet can reappear just after a summary ended the dash.
            // Only hints are provisional: a flow that itself implies Online still starts at once.
            // The window gates ARMING only: a standing pending keeps its original since/deadline
            // even when another hint arrives after the post-end window closes.
            current.mode == Mode.Offline && impliedMode == Mode.Online &&
                obs is Observation.Screen && isHintOnly(flowObs, policy) &&
                (current.pendingModeResume != null || withinPostEndWindow(current, obs, policy)) &&
                (current.session == null || current.pendingDestructive?.let {
                    it.kind == DestructiveKind.SESSION_END && it.authoritative
                } == true) -> armModeResume(current, obs, policy)

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
        startedAt: Long = obs.timestamp,
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
                    // #1029: a running-total read parked before this dash existed
                    // describes the previous one — never settle it against this session.
                    region = SessionPaySettleGate.sessionBoundary(region.copy(
                        session = Session(
                            sessionId = mintId("session", region, obs, timestamp = startedAt),
                            startedAt = startedAt,
                        ),
                        mintCounter = region.mintCounter + 1,
                    ))
                }
            }
            prev.mode != Mode.Offline && newMode == Mode.Offline -> {
                // Non-authoritative offline — arm a provisional dash-end, keeping
                // the session alive until it's confirmed or cancelled.
                if (region.session != null &&
                    region.pendingDestructive?.kind != DestructiveKind.SESSION_END
                ) {
                    // #1078: this arm used to OVERWRITE a standing `TASK_RETIRE` with `SESSION_END`
                    // (the loss documented as a #1076 gap) — the retire's evidence simply vanished
                    // and the eventual teardown force-stamped an unqualified completion. Absorb the
                    // retire's `since` so `endSession` can honor it, through the SAME predicate the
                    // summary arm reads ([absorbableRetire]).
                    val absorbed = region.absorbableRetire()
                    // Round 5: absorbing a retire never PULLS its commit forward. The end may be
                    // delayed to the retire's own deadline, so a contradicting `task:unassigned`
                    // frame the retire would still have seen can still disown it.
                    val candidate = obs.timestamp + policy.gracePeriodMs(region.platform)
                    val (withId, wakeId) = region.mintWakeId()
                    region = withId.copy(
                        pendingDestructive = PendingDestructive(
                            kind = DestructiveKind.SESSION_END,
                            since = obs.timestamp,
                            deadline = maxOf(candidate, absorbed?.deadline ?: candidate),
                            absorbedRetireSince = absorbed?.since,
                            absorbedRetireDeadline = absorbed?.deadline,
                            wakeId = wakeId,
                            windowFrom = obs.timestamp,
                        ),
                    )
                }
            }
        }

        // #1054 round 4: the pause-safety DEADLINE rides the same edge both ways — stamped from
        // the platform's reported countdown plus this platform's buffer, cleared on the way out —
        // and `EffectMap.diffPauseSafetyTimer` turns each into the arm/cancel that used to be
        // hand-built at three sites in `diffMode`. Here for the same reason the park's re-base is:
        // this is the single site that moves `mode`.
        if (prev.mode != Mode.Paused && newMode == Mode.Paused) {
            val remaining = ((obs as? Observation.FlowObservation)?.parsed
                as? ParsedFields.PausedFields)?.remainingMillis ?: 0L
            val (withId, wakeId) = region.mintWakeId()
            region = withId.copy(
                pauseSafety = PendingWake(
                    deadline = obs.timestamp + remaining +
                        policy.pauseTimeoutBufferMs(region.platform),
                    wakeId = wakeId,
                ),
            )
        }
        // Both resume origins resolve on a committed mode change, including authoritative flows
        // that bypass the grace (#1311). A pending must never outlive that transition.
        if (prev.mode != newMode) {
            region = region.copy(
                pauseSafety = if (prev.mode == Mode.Paused) null else region.pauseSafety,
                pendingModeResume = null,
            )
        }

        region = SessionPaySettleGate.modeChanged(region, prev.mode, obs.timestamp, policy)

        return region
    }

    /** One armer for Paused resumes (#605) and post-end hint-only starts (#1311). */
    private fun armModeResume(
        region: PlatformRegion,
        obs: Observation,
        policy: TransitionPolicy,
    ): PlatformRegion {
        val existing = region.pendingModeResume
        val idleFields = if (region.mode == Mode.Offline) {
            ((obs as? Observation.FlowObservation)?.parsed as? ParsedFields.IdleFields)
                ?: existing?.idleFields
        } else null
        val since = existing?.since ?: obs.timestamp
        val deadline = since + policy.pauseResumeGraceMs(region.platform)
        // #1054: keep both the original since and wake identity while the deadline stands.
        if (existing != null && existing.deadline == deadline) {
            return region.copy(
                lastObservedAt = obs.timestamp,
                pendingModeResume = existing.copy(idleFields = idleFields),
            )
        }
        val (withId, wakeId) = region.mintWakeId()
        return withId.copy(
            lastObservedAt = obs.timestamp,
            pendingModeResume = PendingModeResume(
                since = since, deadline = deadline, wakeId = wakeId, idleFields = idleFields,
            ),
        )
    }

    /** Commit either resume origin; a new session starts at the first hint, not the wake (#1311). */
    private fun commitModeResume(
        region: PlatformRegion,
        obs: Observation,
        policy: TransitionPolicy,
    ): PlatformRegion {
        val pending = region.pendingModeResume
        val resumed = applyModeTransition(
            region, Mode.Online, obs, policy,
            startedAt = pending?.since ?: obs.timestamp,
        )
        return pending?.idleFields?.let {
            updateSessionFields(resumed, obs, Flow.Idle, policy, it)
        } ?: resumed
    }

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
                // so it gets grace treatment.
                //
                // #1054 round 4: identity-gated like the graces. The deadline is state now, so a
                // re-pause arms a new one while an old coroutine may still be in flight — and a
                // stale fire carries the PREVIOUS pause's deadline, so it must not end this one.
                // A payload-less fire is honoured only by the legacy rule in [safetyFireIsAuthoritative]
                // (no net in state, or landing at/after the reconstructed one — see #1083 for the
                // rollback residual); otherwise it matches nothing and the pause waits.
                if (prev.mode == Mode.Paused && safetyFireIsAuthoritative(prev, obs)) {
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
     * #1078 round 3 — **disown an absorbed retire**: the two inseparable halves that turn an honored
     * teardown back into a refused one when the platform says the order was abandoned.
     *
     *  1. `absorbedRetireSince` is cleared on the `SESSION_END`, so `endSession` force-stamps
     *     instead of honoring — the shape the amdt-#5 T3 guard exists to refuse; and
     *  2. the active task is marked `unassignedAt` at [at].
     *
     * Half 2 is what makes the refusal BITE: `EffectMap` judges the mint against the PRE-step
     * region, whose pending still carries the absorbed value, so clearing it alone is invisible to
     * [retirePendingForMint] and the close-out sweep would mint anyway. The marker is answered by
     * firewalls that already exist (the close-out `unassignedAt` skip, `isJobPhysicallyComplete`,
     * `detectAcceptMismatch` counting the order as accounted) and it is the truthful record — which
     * is also why `TaskEffects` can emit the proper `TASK_UNASSIGNED` off it.
     *
     * ONE owner, because the abandon can arrive at either moment: ON the commit frame (the
     * lazy-expiry branch) or INSIDE the grace window (before the `Mode.Offline` early return, where
     * `abandonActiveTask` is unreachable).
     */
    private fun disownAbsorbedRetire(region: PlatformRegion, at: Long): PlatformRegion {
        val pend = region.pendingDestructive ?: return region
        return region.copy(
            pendingDestructive = pend.copy(absorbedRetireSince = null),
            activeTask = region.activeTask?.copy(unassignedAt = at),
        )
    }

    /**
     * #1078 — **the retire a `SESSION_END` may absorb the evidence of**, or null. Round 5 returns
     * the whole pending rather than its `since`, because BOTH halves are evidence: the instant it
     * armed ([PendingDestructive.absorbedRetireSince]) and the window it was standing in
     * ([PendingDestructive.absorbedRetireDeadline]).
     *
     * The ONE spelling, read by BOTH arm sites (the authoritative summary arm and the Online→Offline
     * mode arm) so the two can never disagree about what a teardown is allowed to honor.
     *
     * A `TASK_RETIRE` is absorbable only when its PROVENANCE says the task it retires was actually
     * finished. That is exactly `armedFromFlow` (#596), and two values are excluded:
     *
     * - **[Flow.OfferPresented]** — the dasher stepped off the task to deliberate on a mid-route
     *   add-on offer. That drop is NOT delivered, which is why `retireActiveTask` already refuses to
     *   close the job on such a retire; letting a dash end honor it would mint a completion (and an
     *   offer-pay share) for undelivered work — the exact fabrication #1078 exists to prevent, in the
     *   opposite direction.
     * - **[Flow.PostTask]** — a receipt-armed retire's completion is ALREADY minted, by the
     *   PostTask-exit block on the very frame that left the receipt. Honoring it again at the
     *   teardown emits a SECOND raw `DELIVERY_COMPLETED` for the same task: live, the engine's
     *   `effects_fired` per-task key hides it; in a replay (and in any consumer counting raw
     *   effects) it is a double-count.
     * - **`armedFromFlow == null`** — a pre-#596 snapshot's retire, or one whose provenance was never
     *   recorded. Unprovenanced evidence is not evidence: fail-null (#745).
     *
     * **Delivery evidence is an ADDITIONAL condition** (#615/#1101/#1315). T1 close, absorption
     * and endSession share one standard — arrival OR a completion-steps tap the dasher did not
     * navigate away from (#1315), on an un-unassigned DROPOFF. A gift sheet can supply the tap
     * without arrival; SESSION_END inside its retire window must honor it once the window matures,
     * just like T1. [completedInline] lets the shared [isDeliveredEvidenced] predicate see the retire
     * as committed without mutating the task. Provenance still requires a watched retire; evidence
     * alone never authorizes a completion (the rejected rule 2 below).
     *
     * **Rule 2 was considered and REJECTED** (round 2): absorbing at the AUTHORITATIVE summary
     * whenever an ARRIVED dropoff was still active would have covered the retire-less 09-05 shape,
     * but it fabricates a completion for an arrived drop that was cancelled or unassigned through a
     * path the sensor never captured — and for a misrecognized summary. Fail-null beats fail-wrong:
     * the machine records only what it watched finish.
     */
    private fun PlatformRegion.absorbableRetire(): PendingDestructive? {
        val drop = activeTask ?: return null
        if (drop.phase != TaskPhase.DROPOFF) return null
        return pendingDestructive
            ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }
            ?.takeIf { it.armedFromFlow != null && it.armedFromFlow !in UNABSORBABLE_RETIRE_FLOWS }
            ?.takeIf { drop.completedInline(drop.honoredRetireInstant(it.since)).isDeliveredEvidenced() }
    }

    /** #1315: a witness-only completion cannot precede the tap that supplies its evidence. */
    private fun Task.honoredRetireInstant(since: Long): Long =
        if (arrivedAt == null) maxOf(since, completionStepsAt ?: since) else since

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
        var r = region
        // #438 item 5 (D3): the lifecycle edges below diff THIS region's own acted flow, not the
        // shared global R0 flow — see [actedFlowEdge], the one owner of that derivation.
        val (prev, next) = actedFlowEdge(region, prevFlow, obs)

        // Authoritative session end: the dash-summary screen. It no longer ends
        // the session on the spot (#431) — one misrecognized frame used to split
        // a live session irrecoverably. Instead it arms (or tightens) a SHORT
        // authoritative grace: a contradicting task-flow frame inside the window
        // cancels (misrecognition), anything else lets it commit at the deadline
        // (GRACE_COMMIT timer or lazy expiry). The summary's parsed fields ride
        // the pending so the deferred DASH_STOP payload keeps full fidelity.
        // Runs before the Offline early-return below because the summary commonly
        // shows AFTER the idle/offline screen, mid-grace.
        if (obs.flow == Flow.SessionEnded && r.session != null) {
            val endFields = obs.parsed as? ParsedFields.SessionEndedFields
            val newDeadline = obs.timestamp + policy.authoritativeGraceMs(r.platform)
            val existing = r.pendingDestructive
            // #1078: the destructive slot holds ONE pending, and this arm used to REPLACE whatever
            // stood in it — so a `TASK_RETIRE` armed seconds earlier (the dropoff screen giving way
            // to the idle map) was silently discarded, and the eventual `endSession` force-stamped a
            // completion the amdt-#5 T3 guard then refused to mint. Absorb instead of discarding —
            // through the ONE predicate both arm sites read ([absorbableRetire]).
            val absorbed = r.absorbableRetire()
            val pend = if (existing?.kind == DestructiveKind.SESSION_END) {
                // Offline-grace already armed (idle/offline before summary) —
                // tighten to the short window, keep the original `since` (the
                // earliest destructive signal is the honest end time).
                //
                // An already-absorbed retire wins, and its two halves move TOGETHER (round 5) — a
                // `since` from one retire beside a `deadline` from another would be a floor that
                // belongs to neither.
                val keptAbsorbedSince = existing.absorbedRetireSince ?: absorbed?.since
                val keptAbsorbedDeadline = if (existing.absorbedRetireSince != null) {
                    existing.absorbedRetireDeadline
                } else {
                    absorbed?.deadline
                }
                // The tighten may shorten the window — but never below the absorbed retire's own.
                val tightened = minOf(existing.deadline, newDeadline)
                existing.copy(
                    deadline = maxOf(tightened, keptAbsorbedDeadline ?: tightened),
                    authoritative = true,
                    endFields = endFields ?: existing.endFields,
                    absorbedRetireSince = keptAbsorbedSince,
                    absorbedRetireDeadline = keptAbsorbedDeadline,
                )
            } else {
                PendingDestructive(
                    kind = DestructiveKind.SESSION_END,
                    since = obs.timestamp,
                    deadline = maxOf(newDeadline, absorbed?.deadline ?: newDeadline),
                    authoritative = true,
                    endFields = endFields,
                    absorbedRetireSince = absorbed?.since,
                    absorbedRetireDeadline = absorbed?.deadline,
                )
            }
            // #1054 round 5: a MOVED deadline is a new pending as far as its timer is concerned —
            // the standing arm would fire at the old, later instant. An unchanged one (the tighten
            // that did not actually tighten) keeps its id and its live arm.
            // Round 7: the predecessor is passed ONLY when this frame is tightening the SAME kind.
            // The `else` branch built a fresh SESSION_END that REPLACES whatever stood there, and a
            // replacement must never inherit an identity (the installer's kind test is the belt to
            // this call site's braces).
            val tightened = existing?.takeIf { it.kind == DestructiveKind.SESSION_END }
            return r.withWakeIdIfDeadlineMoved(tightened, pend, obs)
        }
        // #1078 round 3: an unassign INSIDE the window disowns too. The lazy-expiry branch only sees
        // an unassign frame that lands at or past the deadline; one arriving EARLIER falls straight
        // through to the `Mode.Offline` early return below — the summary implied Offline, so
        // `updateTaskLifecycle` (where `abandonActiveTask` lives) is never reached, the absorbed
        // value survives untouched, and the eventual wake fabricates the completion for an order the
        // platform said was abandoned. Same two halves, same owner.
        if (obs.flow == Flow.TaskUnassigned &&
            r.pendingDestructive?.kind == DestructiveKind.SESSION_END &&
            r.pendingDestructive?.absorbedRetireSince != null
        ) {
            r = disownAbsorbedRetire(r, obs.timestamp)
        }
        if (r.mode == Mode.Offline) {
            // Clear the idle anchor and any TASK_RETIRE pending, but PRESERVE a
            // SESSION_END pending — that IS the graced-offline state.
            val keptPending = r.pendingDestructive
                ?.takeIf { it.kind == DestructiveKind.SESSION_END }
            return r.copy(idleEnteredAt = null, pendingDestructive = keptPending)
        }

        // Update session fields from observations. `next` is the flow the read is being made
        // ON — the settle gate parks it with that flow (#1029, see [PendingSessionPay.flow]).
        r = updateSessionFields(r, obs, next, policy, obs.parsed)

        // Accumulate delivery pay when entering PostTask
        if (prev != Flow.PostTask && next == Flow.PostTask) {
            // #1033 review rounds 6–7: the instant this delivery's receipt FIRST appeared. The ONE
            // ownership anchor a late-arriving itemization can be judged against — see
            // [ClosedJobReceipt.receiptSeenAt]. Stamped on the flow EDGE (the same edge that arms the
            // receipt's TASK_RETIRE grace), unconditionally (a frame that fails to parse a receipt
            // still put one on screen) — and ONLY when absent: a re-entry after an offer overlay
            // would otherwise move the cutoff forward and step over an accept that resolved during
            // it. Reset with the job, in [closeReceiptRepriceWindow].
            if (r.jobReceiptAnchors == null) {
                r = r.copy(
                    jobReceiptAnchors = JobReceiptAnchors(
                        jobId = r.activeJob?.jobId,
                        firstEnteredAt = obs.timestamp,
                    ),
                )
            }
            val postFields = obs.parsed as? ParsedFields.PostTaskFields
            r = SessionPaySettleGate.receiptEntry(r, postFields, obs.timestamp)
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
        obs: Observation,
        flow: Flow,
        policy: TransitionPolicy,
        parsed: ParsedFields,
    ): PlatformRegion {
        var r = region

        when (parsed) {
            is ParsedFields.IdleFields -> {
                if (parsed.zoneName != null) r = r.copy(zoneName = parsed.zoneName)
                if (parsed.sessionType != null) r = r.copy(sessionType = parsed.sessionType)
                r = SessionPaySettleGate.pillRead(r, parsed.sessionPay, flow, obs.timestamp, policy)
            }
            is ParsedFields.PostTaskFields -> {
                // Stamp the per-task announcement gate so EffectMap.diffPostTask
                // can detect "first time seeing PostTask for this taskId". The
                // completing task is the still-active one while its retire
                // grace is pending (#431 pt 2), falling back to the last
                // committed task. MUST be the same resolution diffPostTask
                // uses — the old recentTasks-only stamp lagged the commit by
                // one frame and double-fired the receipt bubble on the
                // expanded re-observation. Since #1073 round 14 BOTH read the
                // one [receiptSubjectTaskId] resolver (the active dropoff first,
                // arrival or not; else the job's last completed one; never a pickup).
                val postTaskTaskId = r.receiptSubjectTaskId()
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
                if (postTaskTaskId == null) {
                    // #1073: no nameable SUBJECT — no dropoff active and none completed — so this
                    // receipt describes nobody in this region and refreshes NOTHING. Fail-null
                    // (#745); silent, because the absent announce is itself the observable (no
                    // "Saved: \$X" bubble fires) — unlike #1103's refusal below, which leaves no
                    // observable at all and therefore carries the one DEBUG line in this reducer.
                } else if (!sameTaskCollapsedDowngrade) {
                    // #1033 round 13: ONE owner writes the receipt, the drops it describes, and the
                    // task it was announced for — see [cacheReceipt] / [ReceiptCoverage].
                    r = cacheReceipt(r, parsed, postTaskTaskId)
                }
                // #1033 round 4: decide the late-receipt re-price HERE, where state can be written —
                // the itemization has just landed in `lastPostTaskFields`, and the marker has to be
                // updated in the same breath (see [PendingReceiptReprice]). `EffectMap` only emits
                // what this decides. The flow gate lives at this call site (round 9), because the
                // OTHER caller is the job close, which has no frame.
                if (flow == Flow.PostTask) {
                    r = decideReceiptReprice(
                        region = r,
                        parsed = parsed,
                        postTaskTaskId = postTaskTaskId,
                        coverage = r.lastPostTaskCoverage,
                        decidedAt = obs.timestamp,
                        captureId = obs.captureId,
                    )
                }
                r = SessionPaySettleGate.receiptRead(r, obs, parsed, flow, policy)
            }
            is ParsedFields.SessionEndedFields -> {
                // #1030: only a REAL parse moves `runningEarnings` — a missed total is null now,
                // and writing it through would zero a figure the summary never contradicted.
                val total = parsed.totalEarnings
                if (total != null) {
                    r = SessionPaySettleGate.summaryTotal(r, total, obs.timestamp)
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
                overallRatingPoints = parsed.overallRatingPoints,
                tierLabel = parsed.tierLabel,
                qualityRate = parsed.qualityRate,
            ),
        )
    }

    /**
     * #1312's marked-idle abandon may arm only into an empty destructive slot. Preserve an existing
     * pending: replacing it would discard that decision's evidence, deadline and wake identity.
     */
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

        // #1312: the marked NEXT flow-bearing frame arms a cancellable whole-job abandon.
        // Bare Idle can be navigation; recognize-only frames leave R0 unchanged.
        if (prevFlowVal == Flow.TaskUnassigned && nextFlowVal == Flow.Idle &&
            (obs as? Observation.Screen)?.idleSurface == IdleSurface.NO_ORDERS && obs.modeHint == Mode.Online &&
            current.activeTask == null && current.activeJob != null && current.pendingDestructive == null
        ) {
            return current.withWakeIdIfDeadlineMoved(
                prev = null,
                obs = obs,
                next = PendingDestructive(
                    kind = DestructiveKind.JOB_ABANDON,
                    since = obs.timestamp,
                    deadline = obs.timestamp + policy.jobAbandonGraceMs(current.platform),
                    armedFromFlow = Flow.TaskUnassigned,
                ),
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
            return completeActiveJob(current, obs.timestamp)
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
        observedAt: Long,
    ): PlatformRegion = when (kind) {
        DestructiveKind.SESSION_END -> endSession(region, timestamp, observedAt)
        // [timestamp] is the honest completion instant (the grace's `since`, #732); [observedAt] is
        // the frame that commits it, which is what a re-price decided here must be stamped with.
        DestructiveKind.TASK_RETIRE -> retireActiveTask(region, timestamp, observedAt)
        DestructiveKind.JOB_ABANDON -> abandonActiveJob(
            region.copy(pendingDestructive = null), timestamp, reason = "unassign then no-orders idle (#1312)",
            arrivedDropsComplete = false,
        )
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
    private fun retireActiveTask(
        region: PlatformRegion,
        timestamp: Long,
        observedAt: Long = timestamp,
    ): PlatformRegion {
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
            completeActiveJob(retired, observedAt)
        } else {
            retired
        }
    }

    /**
     * End the old session, preserving an Offline-origin pending start; an Online region must fall
     * back Offline because an authoritative end can survive an ungraced Online frame (#1311).
     */
    private fun endSession(
        region: PlatformRegion,
        timestamp: Long,
        observedAt: Long = timestamp,
    ): PlatformRegion {
        // #1033 round 10: a terminal session end tears down the job WITHOUT going through
        // [completeActiveJob], so it bypassed the close-time re-price decision entirely — and then
        // cleared the cached receipt. The fielded shape: an offer overlay pops a collapsed receipt
        // (the completion mints at the estimate and burns its durable key), the receipt returns
        // EXPANDED, and the dash ends before any further frame. Ask here too, from the receipt this
        // teardown is about to drop, under exactly the same guards.
        //
        // The denominator is the load-bearing part. `pendingDestructive` is the SESSION_END that
        // brought us here, so [mintQualified] already excludes the active task — which is right for a
        // drop this bail is force-completing (the amdt-#5 T3 guard: it has no row), and wrong for one
        // whose completion was already minted on an earlier PostTask exit. `exitedPostTask` is what
        // separates them; a mistake either way is caught by the projector finding no row.
        // KEY SAFETY (round 11): the synthesized marker starts at revision 0, so its decision emits
        // `…:<taskId>:<jobId>:r1` — the same key a job's FIRST post-close decision would use. The two
        // can never both exist for one jobId: a post-close decision requires `completeActiveJob` to
        // have run for that job, which leaves `activeJob` null, and this branch is guarded on
        // `activeJob != null`. A job is therefore either closed-then-re-priced (real marker, revisions
        // from the close) or active-at-teardown (synthesized marker, revision 1) — never both. A
        // DIFFERENT job that closed earlier is a different `jobId`, which is in the key.
        //
        // #1073 round 13: the receipt here is the CACHED one, so the drops it DESCRIBED when it was
        // read scope the denominator ([ReceiptCoverage]); a null coverage (a pre-round-13 snapshot)
        // decides nothing — fail-null (#745). Shared with the job close through
        // [decideFromCachedReceipt]: one question, one implementation.
        val endingJob = region.activeJob
        val repriceHandoff = endingJob?.let { job ->
            decideFromCachedReceipt(
                source = region,
                closed = region.copy(
                    activeJob = null,
                    lastClosedJobReceipt = ClosedJobReceipt(
                        jobId = job.jobId,
                        receiptSeenAt = region.jobReceiptAnchors?.firstEnteredAt,
                    ),
                ),
                decidedAt = observedAt,
                mintRanForJob = region.jobReceiptAnchors?.exitedPostTask == true,
            ).pendingReceiptReprice
        }
        // #1078: two shapes of teardown completion, and the destructive pending says which.
        //  - HONORED: this `SESSION_END` absorbed a PROVENANCED live `TASK_RETIRE` **and the retire
        //    has MATURED** — the task is retired at THAT instant, exactly as the retire's own expiry
        //    would have done, through the one spelling of the retire copy ([completedInline]).
        //  - FORCE-STAMPED (the pre-#1078 shape, unchanged): no absorbed evidence, or evidence that
        //    has not matured. `completedAt` is stamped at the teardown clock so the job's shape stays
        //    readable, and the amdt-#5 T3 guard deliberately refuses to mint a row for it.
        //
        // Round 6 — the MATURITY gate. The absorbed retire's deadline floors the pending's own
        // deadline, but not every teardown arrives through that deadline: the mode arm's
        // `IdleFields(startingSession = true)` shortcut calls `endSession` on the spot, bypassing the
        // floor entirely, and the honored completion committed for a retire that still had seconds to
        // run — seconds in which a `task:unassigned` frame could still have disowned it. A dash
        // ending early is confirmation that the DASH ended; it says nothing about whether the retire
        // matured. So honor only from the absorbed deadline onward, and force-stamp before it.
        //
        // Nothing else needs clearing: the honored branch below MARKS the task
        // ([Task.honoredRetireAt]) and that mark is the emitter's discriminator (`mintQualified`), so
        // a force-stamp here is automatically refused a row.
        val pend = region.pendingDestructive?.takeIf { it.kind == DestructiveKind.SESSION_END }
        val matured = observedAt >= (pend?.absorbedRetireDeadline ?: Long.MIN_VALUE)
        // The same delivery evidence gate as T1 and absorption, including restored absorbed state.
        val honoredAt = region.activeTask?.let { task ->
            pend?.absorbedRetireSince?.let { task.honoredRetireInstant(it) }?.takeIf { at ->
                matured && task.phase == TaskPhase.DROPOFF && task.completedInline(at).isDeliveredEvidenced()
            }
        }
        val completedTask = region.activeTask?.let {
            if (honoredAt != null) {
                // Round 7: STATE the honor on the task (`Task.honoredRetireAt`) instead of leaving the
                // emitter to infer it from `completedAt`'s value. `pend.since` and
                // `absorbedRetireSince` can be the SAME instant (a summary frame sharing the idle
                // frame's timestamp, or a clock rollback), and then the force-stamp below is
                // indistinguishable from an honored one.
                it.completedInline(honoredAt).copy(honoredRetireAt = honoredAt)
            } else {
                it.copy(completedAt = timestamp)
            }
        }
        val recentTasks = if (completedTask != null) {
            (region.recentTasks + completedTask).takeLast(MAX_RECENT_TASKS)
        } else region.recentTasks

        // #1073 round 13: `clearCachedReceipt` drops the receipt AND its coverage — one clearer,
        // shared with [completeActiveJob], so the two can never fall out of step.
        // #1029: an un-settled running-total read describes the dash that just ended.
        return SessionPaySettleGate.sessionBoundary(region.clearCachedReceipt().copy(
            session = null,
            mode = if (region.mode == Mode.Online) Mode.Offline else region.mode,
            lastSessionEndedAt = if (region.session != null) timestamp else region.lastSessionEndedAt,
            activeJob = null,
            activeTask = null,
            recentTasks = recentTasks,
            pendingDestructive = null,
            // A Paused resume belongs to the ended session; an Offline pending is a NEW start.
            pendingModeResume = region.pendingModeResume.takeIf { region.mode == Mode.Offline },
            idleEnteredAt = null,
            // #438 B3: the session's over — pending/accepted offers must not leak into the next dash.
            pendingOffers = emptyList(),
            // #1033: the receipt marker belongs to the dash that just ended — a stale one must not
            // let a next-dash receipt frame re-price the previous dash's drops. Its two anchors go
            // with it (round 6): an accept and a receipt both belong to the dash they happened in.
            lastClosedJobReceipt = null,
            jobReceiptAnchors = null,
            lastAcceptResolvedAt = null,
            // The one thing that OUTLIVES the teardown: a correction decided from the receipt it just
            // dropped. `EffectMap` emits it on this same step; the next step's top-of-step clear
            // drops it (#1033 round 10).
            pendingReceiptReprice = repriceHandoff,
        ))
    }

    internal fun completeActiveJob(region: PlatformRegion, at: Long): PlatformRegion {
        val job = region.activeJob ?: return region
        val closed = region.clearCachedReceipt().copy(
            activeJob = null,
            // #1033 layer 2: record what the receipt looked like BEFORE this line drops it. Closing
            // the job is the step on which the `DELIVERY_COMPLETED`s for its delivered drops are
            // minted (the #596 close-out sweep, or the PostTask-exit block on this same step), and
            // both read `lastPostTaskFields` from the PRE-step region — so this marker is exactly
            // what those payloads carried. Without it a receipt EXPANDED after the close cannot be
            // told apart from a re-render of one the completions already priced off.
            // #1033 rounds 6–8: the marker carries the OWNERSHIP anchor and nothing about what the
            // rows hold — modelling that was two rounds of fail-null, and the projector can just look.
            // The anchor is deliberately NOT cleared here: the receipt is still on screen after the
            // close, and a later expansion is judged against it.
            lastClosedJobReceipt = ClosedJobReceipt(
                jobId = job.jobId,
                receiptSeenAt = region.jobReceiptAnchors?.firstEnteredAt,
            ),
        )
        // #1033 round 9: ask ONCE MORE, from the receipt this close is about to drop.
        //
        // A job whose ITEMIZED receipt was already on screen when it closed may never render another
        // frame — and its row can still be un-itemized, because its first completion was minted on an
        // earlier PostTask exit (an offer overlay popping the receipt, say) and the close's
        // re-emission is dropped by the per-taskId `effects_fired` key. `lastPostTaskFields` is the
        // last place that evidence exists; one line later it is gone.
        //
        // Same guards, same denominator, same handoff — `closed` already has `activeJob = null` and
        // the fresh marker, so the ownership rules read exactly as they do on a frame. The projector's
        // no-op makes this free whenever the mint DID carry the itemization. If a receipt frame on
        // this same step then decides something newer, it overwrites this handoff: that is correct
        // (the frame is fresher evidence for the same job) and costs only a skipped revision number.
        //
        // #1073 round 13: shared with the teardown — same question, same [ReceiptCoverage] scoping.
        return decideFromCachedReceipt(source = region, closed = closed, decidedAt = at)
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

private fun postEndAnchor(region: PlatformRegion): Long? =
    listOfNotNull(
        region.lastSessionEndedAt,
        region.pendingDestructive?.takeIf {
            it.kind == DestructiveKind.SESSION_END && it.authoritative
        }?.since,
    ).maxOrNull()

private fun PlatformRegionStepper.withinPostEndWindow(
    region: PlatformRegion,
    obs: Observation,
    policy: TransitionPolicy,
): Boolean = postEndAnchor(region)?.let {
    obs.timestamp - it <= policy.postEndHintGraceWindowMs(region.platform)
} == true

private fun isHintOnly(flowObs: Observation.FlowObservation, policy: TransitionPolicy): Boolean =
    policy.resolveMode(flowObs.flow, null) == null
