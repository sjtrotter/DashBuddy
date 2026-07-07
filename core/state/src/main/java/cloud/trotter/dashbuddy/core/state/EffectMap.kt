package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.action.ActionTrigger
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.AppEventPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionEndSource
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferReceivedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionPausedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.model.pay.displayLabel
import cloud.trotter.dashbuddy.domain.settings.GraceConfig
import cloud.trotter.dashbuddy.domain.settings.GraceConfigProvider
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ParsedFieldsGate
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.DropPayApportioner
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferPayFallback
import cloud.trotter.dashbuddy.domain.state.TransitionKind
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PickupActivity
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.state.UNKNOWN_STORE
import cloud.trotter.dashbuddy.domain.state.customerLabel

/**
 * Replaces 9 reducers + 8 factories. Diffs each region before/after
 * stepping and emits the appropriate [AppEffect] list.
 *
 * Flow-region transitions → UI overlay effects (offer evaluation, screenshot).
 * Platform-region transitions → durable effects (odometer, session, event log).
 * Cross-platform transitions → aggregate bookkeeping (currently none).
 */
@Singleton
class EffectMap @Inject constructor(
    /**
     * Per-platform grace/timing snapshot (#438 item 6, vet M7). Same
     * eagerly-materialized synchronous value provider `TransitionPolicy` takes —
     * read once per [diff], never collected inside a reducer; defaults to code
     * constants when unbound so `EffectMap()` in tests is behavior-identical.
     * Replay-determinism tradeoff pre-accepted (see [GraceConfigProvider]).
     */
    private val graceConfig: GraceConfigProvider,
) {

    /** Test/default convenience — code-constant timing for every platform. */
    constructor() : this(GraceConfigProvider.Defaults)

    companion object {
        /**
         * Safety buffer added to the platform's reported pause countdown
         * before scheduling the offline timeout. Accounts for clock skew
         * between the parsed timestamp and the actual timer start. Re-exported
         * from [GraceConfig] (the SSOT) for comment/test back-compat; the live
         * value is now per-platform via [graceConfig].
         */
        const val PAUSE_TIMEOUT_BUFFER_MS = GraceConfig.PAUSE_TIMEOUT_BUFFER_MS

        /**
         * Settle delay before the EXPAND_EARNINGS tap (#425) — lets the
         * post-delivery summary finish animating so the bound chevron's
         * fingerprint still matches what gets tapped. Carried over from the
         * former rule-declared click's `delayMs`. Re-exported from [GraceConfig]
         * (the SSOT); the live value is now per-platform via [graceConfig].
         */
        const val EXPAND_SETTLE_MS = GraceConfig.EXPAND_SETTLE_MS

        /**
         * #438 B3 (vet H1/L5): de-facto offer TTL when the offer rule parses no countdown (none do
         * today) — the [cloud.trotter.dashbuddy.domain.pipeline.TimeoutType.OFFER_EXPIRY] fires this
         * long after `presentedAt`, guaranteeing a presented offer that vanishes without a frame is
         * eventually resolved. Long enough it can never fire before a real accept/decline lands.
         */
        const val OFFER_EXPIRY_DEFAULT_MS = 120_000L
    }


    fun diff(prev: AppState, next: AppState, obs: Observation): List<AppEffect> = buildList {
        addAll(diffRuleEffects(obs))
        addAll(diffExpandAction(obs))
        addAll(diffConfirmDeclineAction(obs, next))
        addAll(diffSettleUiTimeout(obs))
        // #438 B3: a HUD accept/decline resolves by the tap's OWN carried (platform, offerHash)
        // against that platform's owned offers — no global-flow precondition (vet M4).
        addAll(diffOfferAction(obs, next))
        // #438 B5 (item 9): odometer arbitration is a CROSS-PLATFORM decision — one GPS, one global
        // total — so it diffs the aggregate ONCE, not per platform region.
        addAll(diffCrossPlatform(prev, next))
        val allPlatforms = (prev.regions.platforms.keys + next.regions.platforms.keys).distinct()
        for (p in allPlatforms) {
            addAll(
                diffPlatformRegion(
                    p,
                    prev.regions.platforms[p],
                    next.regions.platforms[p],
                    prev.regions.flow,
                    next.regions.flow,
                    obs,
                )
            )
        }
    }

    // =========================================================================
    // CROSS-PLATFORM DIFFS
    // =========================================================================

    /**
     * #438 B5 (item 9): the odometer arbitration. The four odometer effects moved OFF each
     * platform's session/task diff — where a 2nd concurrent session's Start zeroed the 1st's
     * miles, the 1st ending killed GPS under the 2nd, and one platform's arrival paused GPS on
     * the other's drive — onto ONE cross-platform decision:
     *  - **Start/Stop** on the live-session count crossing 0↔1 (`activeSessionCount`, derived by
     *    [CrossPlatformRegionStepper]; the vet confirmed the count includes paused + grace-window
     *    sessions — correct for "is any dash open").
     *  - **Pause/Resume** on the [OdometerArbiter] stationary level, gated to a *continuously*-live
     *    window (both counts > 0) so a session start/end edge routes through Start/Stop and never a
     *    phantom Pause/Resume (e.g. ending a dash while parked at a drop must Stop, not Resume).
     *    Pause fires when every live region becomes stationary; Resume when any starts moving.
     *
     * Single-platform behavior is byte-identical in GPS on/off state (proven against the replay
     * fixtures, [cloud.trotter.dashbuddy] `OdometerPredicateEquivalenceTest`); the only difference
     * is this elides today's redundant Resume-while-already-moving emissions (session-start pickup
     * nav, PostTask entry from a non-arrived drive), which `startTracking()` already no-ops.
     */
    private fun diffCrossPlatform(prev: AppState, next: AppState): List<AppEffect> = buildList {
        val prevCount = prev.regions.crossPlatform.activeSessionCount
        val nextCount = next.regions.crossPlatform.activeSessionCount
        when {
            prevCount == 0 && nextCount > 0 -> add(AppEffect.StartOdometer)
            prevCount > 0 && nextCount == 0 -> add(AppEffect.StopOdometer)
        }
        if (prevCount > 0 && nextCount > 0) {
            val prevStationary = OdometerArbiter.allLiveStationary(prev.regions.platforms)
            val nextStationary = OdometerArbiter.allLiveStationary(next.regions.platforms)
            when {
                !prevStationary && nextStationary -> add(AppEffect.PauseOdometer)
                prevStationary && !nextStationary -> add(AppEffect.ResumeOdometer)
            }
        }
    }

    // =========================================================================
    // PLATFORM REGION DIFFS
    // =========================================================================

    private fun diffPlatformRegion(
        platform: Platform,
        prev: PlatformRegion?,
        next: PlatformRegion?,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation,
    ): List<AppEffect> {
        if (next == null) return emptyList()
        val p = prev ?: PlatformRegion(platform)

        // #438 item 5 (D3): the lifecycle edges below diff THIS region's own acted flow, not the
        // shared global R0 flow — `diff` iterates every platform, but under concurrency R0.flow is
        // whatever platform last touched the screen (so a foreign frame used to fire this platform's
        // PostTask edges). Each side falls back to the matching GLOBAL flow only while
        // lastActedFlow is null, which reproduces the pre-B1 behavior byte-for-byte. The fallback
        // is never taken for a region that acted POST-B1 (its first own flow frame stamps it);
        // a legacy pre-B1 snapshot decodes task-owning regions with lastActedFlow=null and keeps
        // pre-B1 behavior until each region's first own frame heals it — a one-shot, accepted
        // residual. Under B1 the observing region stamps its own flow, so a stamped non-observing
        // region — where p === next → actedPrev == actedNext — never sees an edge.
        val actedPrevFlow = p.lastActedFlow ?: prevFlow.flow
        val actedNextFlow = next.lastActedFlow ?: nextFlow.flow

        return buildList {
            // #438 B3: offers are now per-platform durable state — their effect diffs join the other
            // per-region lifecycle diffs (received/replaced/eval-landed/resolved/click-ack + expiry
            // arm/cancel), extracted to OfferEffects (vet L4). Session-scoped so AppEventDao dashId
            // queries see the offer events (#257) — the region's own session, per-platform.
            val offerSessionId = next.session?.sessionId ?: p.session?.sessionId
            addAll(diffOfferLifecycle(p, next, obs, offerSessionId))
            addAll(diffMode(p, next, obs))
            addAll(diffGraceTimer(p, next, obs))
            addAll(diffModeResumeTimer(p, next, obs))
            addAll(diffTask(p, next, obs))
            addAll(diffPostTask(p, next, actedNextFlow, obs))
            addAll(diffNotification(obs))

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
                    // The delivered task may still be ACTIVE on PostTask exit —
                    // its retire grace commits up to ~2.5s later (#431 pt 2).
                    // Prefer it (same-id guard: a stacked next-task frame mints
                    // a NEW activeTask on this same exit, which must not be the
                    // one we report); fall back to the last committed task.
                    // #518: resolve the task being completed — the still-active delivered task, or
                    // the one just retired into recentTasks on this exit, JOB-SCOPED so a PRIOR job's
                    // stale task can never be the fallback (the cross-job leak, db seq 117/100 — a
                    // prior job's already-completed dropoff re-firing under the new job). When there
                    // is no active job to scope by, keep the prior unscoped behaviour (the payload's
                    // jobId would be null regardless).
                    val completedJobId = p.activeJob?.jobId
                    // #596 amdt 2: when there's genuinely nothing being completed on this exit —
                    // job already closed (by T1 on a prior step), no active task, no retire pending —
                    // the unscoped (job-less) fallback must NOT grab a stale recentTask and re-fire a
                    // completion the close-out block already minted. The scoped arm (job present) is
                    // unaffected.
                    val allowUnscopedFallback =
                        !(p.activeJob == null && p.activeTask == null &&
                            p.pendingDestructive?.kind != DestructiveKind.TASK_RETIRE)
                    val completedTask = next.activeTask?.takeIf { it.taskId == p.activeTask?.taskId }
                        ?: next.recentTasks.lastOrNull {
                            if (completedJobId == null) allowUnscopedFallback else it.jobId == completedJobId
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
                    if (completedTask != null && completedTask.phase == TaskPhase.DROPOFF && !identityLess) {
                        val retireSince = p.pendingDestructive
                            ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }?.since
                        // #528: attribute this drop's share of the combined receipt. Read the
                        // receipt from the PREV region's lastPostTaskFields (the singular job
                        // receipt) directly — NOT the per-task pinning — so every drop of a stack
                        // gets a share, not just the announced one.
                        val dropShare = DropPayApportioner.apportion(
                            parsedPay = p.lastPostTaskFields?.parsedPay,
                            dropoffTasks = jobDropoffTasks(next, p.activeJob?.jobId),
                        )[completedTask.taskId]
                        // #691: when the whole job was receipt-less, stamp this drop's equal-split
                        // share of the accepted-offer pay so it folds a real net row (not $0-unattr).
                        // FIX 1: a PostTask-exit mint's job may still be OPEN — stamp only when this is
                        // the LAST OPEN owed dropoff (requireFinalShape), so a mid-stack pay-less exit
                        // can't over-count (estimate-then-late-receipt / add-on drift / cents drift).
                        val offerShare = offerPayShareFor(
                            region = p,
                            job = p.activeJob,
                            taskId = completedTask.taskId,
                            requireFinalShape = true,
                        )
                        val payload = deliveryCompletedPayload(
                            task = completedTask,
                            jobId = p.activeJob?.jobId,
                            completedAt = completedTask.completedAt ?: retireSince ?: obs.timestamp,
                            postTaskFields = p.lastPostTaskFields,
                            sessionEarnings = next.session?.runningEarnings ?: p.session?.runningEarnings,
                            dropRealizedPay = dropShare,
                            offerPayShare = offerShare,
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
                    addAll(pickupConfirmSweepEffects(sessionId, next, closedJob.jobId, obs))
                }
                val retirePending = p.pendingDestructive?.kind == DestructiveKind.TASK_RETIRE
                // #528: split the combined receipt across the job's delivered drops once, so each
                // close-out completion carries its own share (the receipt-skip null rows and the
                // one over-full row become per-drop shares that sum to the receipt total).
                val dropShares = DropPayApportioner.apportion(
                    parsedPay = p.lastPostTaskFields?.parsedPay,
                    dropoffTasks = jobDropoffTasks(next, closedJob.jobId),
                )
                for (task in next.recentTasks) {
                    if (task.jobId != closedJob.jobId || task.phase != TaskPhase.DROPOFF) continue
                    val completedAt = task.completedAt ?: continue
                    // #498 identity firewall (guardrail): never complete an identity-less phantom.
                    if (task.customerNameHash == null && task.customerAddressHash == null) continue
                    // amdt #2 exclusivity: the PostTask-exit block already minted this one.
                    if (task.taskId in emittedThisStep) continue
                    // amdt #5: qualify ONLY (a) a task already completed BEFORE this step, or (b) the
                    // active task just retired under a TASK_RETIRE grace. This excludes exactly
                    // endSession's force-stamp of an active, UNDELIVERED task (T3 false-completion
                    // guard) — that task carries no TASK_RETIRE pending, so neither arm matches.
                    val alreadyCompleted =
                        p.recentTasks.any { it.taskId == task.taskId && it.completedAt != null }
                    val justRetiredUnderGrace = retirePending && p.activeTask?.taskId == task.taskId
                    if (!alreadyCompleted && !justRetiredUnderGrace) continue
                    // amdt #3: attach the receipt's pay ONLY when the receipt was announced for THIS
                    // task (mirror the PostTask path's per-task pinning). A receipt-less completion
                    // naturally gets null pay (#528's job), never a normal receipted delivery's pay.
                    val postTaskFields = p.lastPostTaskFields
                        ?.takeIf { p.lastAnnouncedPostTaskTaskId == task.taskId }
                    // #691: eligibility is JOB-scoped on the whole job's receipt state
                    // (p.lastPostTaskFields), not the per-task-pinned `postTaskFields` above — a
                    // receipt-less close-out (no pay screen at all) stamps every owed drop's
                    // equal-split offer share; a job that showed any PAY-BEARING receipt stamps none.
                    // The close-out job is already CLOSED → its shape is final (requireFinalShape=false).
                    val offerShare = offerPayShareFor(
                        region = p,
                        job = closedJob,
                        taskId = task.taskId,
                        requireFinalShape = false,
                    )
                    val payload = deliveryCompletedPayload(
                        task = task,
                        jobId = closedJob.jobId,
                        completedAt = completedAt,
                        postTaskFields = postTaskFields,
                        sessionEarnings = next.session?.runningEarnings ?: p.session?.runningEarnings,
                        dropRealizedPay = dropShares[task.taskId],
                        offerPayShare = offerShare,
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
    }

    private fun diffMode(
        prev: PlatformRegion,
        next: PlatformRegion,
        obs: Observation,
    ): List<AppEffect> {
        val prevSession = prev.session
        val nextSession = next.session
        val sessionId = nextSession?.sessionId ?: prevSession?.sessionId
        // Finalize when the session actually ENDS (goes null, or is replaced by a
        // different sessionId) — NOT on the bare offline mode-flip. A graced
        // (maybe-transient) offline keeps the session alive, so a summary that
        // arrives after the idle/offline screen still attributes to it; a real
        // end (summary either ordering, grace expiry, or a fresh dash replacing
        // the old one) flips this true.
        val prevEnded = prevSession != null &&
            (nextSession == null || nextSession.sessionId != prevSession.sessionId)
        if (prev.mode == next.mode && !prevEnded) return emptyList()

        return buildList {
            if (prevEnded) {
                // A rule-defined MODE_TO_OFFLINE override (only on an actual mode
                // flip to offline) replaces the default finalize, as before.
                val offlineOverride =
                    if (prev.mode != Mode.Offline && next.mode == Mode.Offline) {
                        triggerOverrideEffects(obs, TransitionTrigger.MODE_TO_OFFLINE)
                    } else {
                        null
                    }
                if (offlineOverride != null) {
                    addAll(offlineOverride)
                } else {
                    // Summary fields: from the committing observation when it IS
                    // the summary, else from the grace pending that stashed them
                    // at arm time (#431) — deferred commits (GRACE_COMMIT timer,
                    // lazy expiry) keep full payload fidelity. endedAt = when the
                    // destructive signal appeared, not when we got around to
                    // believing it.
                    val pend = prev.pendingDestructive
                        ?.takeIf { it.kind == DestructiveKind.SESSION_END }
                    val endParsed = ((obs as? Observation.FlowObservation)?.parsed
                        as? ParsedFields.SessionEndedFields)
                        ?: pend?.endFields
                    val endedAt = pend?.since ?: obs.timestamp
                    if (endParsed != null) {
                        val earnings = Formats.money(endParsed.totalEarnings)
                        add(
                            logEffect(
                                sessionId,
                                AppEventType.DASH_STOP,
                                obs.timestamp,
                                SessionStopPayload(
                                    sessionId = sessionId,
                                    endedAt = endedAt,
                                    source = SessionEndSource.SUMMARY_SCREEN,
                                    totalEarnings = endParsed.totalEarnings,
                                    sessionDurationMillis = endParsed.sessionDurationMillis,
                                    offersAccepted = endParsed.offersAccepted,
                                    offersTotal = endParsed.offersTotal,
                                    weeklyEarnings = endParsed.weeklyEarnings,
                                    platform = prev.platform.name, // #314 capture-gap: harden the log
                                ),
                            ),
                        )
                        // #438 B5: StopOdometer now emits from diffCrossPlatform on the
                        // activeSessionCount 1→0 crossing (this session end is that crossing when
                        // it's the last live session), so one platform ending can't kill GPS under
                        // another still-live dash.
                        add(AppEffect.UpdateBubble("Session Ended. Total: $earnings", ChatPersona.Dispatcher))
                        // #606: no CaptureScreenshot here — the dash_summary
                        // rule effect (doordash.json) already owns the
                        // DashSummary screenshot (deduped + throttled, fires
                        // on recognition). This commit-side add had a null
                        // effectKey, bypassing both effects_fired and the
                        // throttle, so a session end double-fired the shot
                        // ~2.5s apart (the AUTHORITATIVE_GRACE_MS window).
                    } else {
                        add(
                            logEffect(
                                sessionId,
                                AppEventType.DASH_STOP,
                                obs.timestamp,
                                SessionStopPayload(
                                    sessionId = sessionId,
                                    endedAt = endedAt,
                                    source = SessionEndSource.EARLY_OFFLINE,
                                    totalEarnings = prevSession.runningEarnings,
                                    platform = prev.platform.name, // #314 capture-gap: harden the log
                                ),
                            ),
                        )
                        // #438 B5: StopOdometer arbitrated at the cross-platform tier (see above).
                    }
                    add(AppEffect.EndSession(prev.platform.name))
                }
            }

            when {
                // Session start: offline/paused → online
                prev.mode != Mode.Online && next.mode == Mode.Online -> {
                    val modeOverride = triggerOverrideEffects(obs, TransitionTrigger.MODE_TO_ONLINE)
                    if (modeOverride != null) {
                        addAll(modeOverride)
                    } else if (nextSession != null && prevSession?.sessionId != nextSession.sessionId) {
                        val payload = SessionStartPayload(
                            sessionId = nextSession.sessionId,
                            platform = next.platform.name,
                            startedAt = nextSession.startedAt,
                            source = if (next.lastTransitionKind == TransitionKind.Unexpected) {
                                SessionStartSource.RECOVERY
                            } else {
                                SessionStartSource.INTERACTION
                            },
                            startScreen = "WaitingForOffer",
                        )
                        add(logEffect(nextSession.sessionId, AppEventType.DASH_START, obs.timestamp, payload))
                        // #438 B5: StartOdometer now emits from diffCrossPlatform on the
                        // activeSessionCount 0→1 crossing, so a SECOND concurrent session starting
                        // does NOT re-fire Start (which would zero the first's accrued miles). The
                        // per-session anchor rides StartSession (odometerRepository.startSessionTracking).
                        add(AppEffect.StartSession(nextSession.sessionId, next.platform.name))
                    } else if (nextSession != null && prevSession?.sessionId == nextSession.sessionId) {
                        // Grace resume — same session, no start effects needed
                        Timber.d("Session grace resume: ${nextSession.sessionId}")
                        add(AppEffect.UpdateBubble("Session resumed (grace)"))
                    }

                    // Cancel pause safety timer if resuming from paused
                    if (prev.mode == Mode.Paused) {
                        val resumeOverride = triggerOverrideEffects(obs, TransitionTrigger.RESUME_FROM_PAUSE)
                        if (resumeOverride != null) {
                            addAll(resumeOverride)
                        } else {
                            add(AppEffect.CancelTimeout(TimeoutType.SESSION_PAUSED_SAFETY, next.platform))
                        }
                    }
                }

                // Going offline. Session finalize (and any MODE_TO_OFFLINE
                // override) is handled by the `prevEnded` block above, which
                // defers while a grace window keeps the session alive. Only the
                // pause-safety-timer cancel remains here.
                prev.mode != Mode.Offline && next.mode == Mode.Offline -> {
                    // Cancel pause safety timer
                    if (prev.mode == Mode.Paused) {
                        val resumeOverride = triggerOverrideEffects(obs, TransitionTrigger.RESUME_FROM_PAUSE)
                        if (resumeOverride != null) {
                            addAll(resumeOverride)
                        } else {
                            add(AppEffect.CancelTimeout(TimeoutType.SESSION_PAUSED_SAFETY, next.platform))
                        }
                    }
                }

                // Pause: online → paused
                prev.mode == Mode.Online && next.mode == Mode.Paused -> {
                    val modeOverride = triggerOverrideEffects(obs, TransitionTrigger.MODE_TO_PAUSED)
                    if (modeOverride != null) {
                        addAll(modeOverride)
                    } else {
                        val flowObs = obs as? Observation.FlowObservation
                        val pausedFields = flowObs?.parsed as? ParsedFields.PausedFields
                        val durationMs = (pausedFields?.remainingMillis ?: 0L) +
                            graceConfig.forPlatform(next.platform).pauseTimeoutBufferMs

                        val pausePayload = SessionPausedPayload(
                            sessionId = sessionId,
                            pausedAt = obs.timestamp,
                            remainingText = pausedFields?.remainingText,
                            remainingMillis = pausedFields?.remainingMillis,
                            platform = next.platform.name, // #314 capture-gap: harden the log
                        )
                        add(logEffect(sessionId, AppEventType.DASH_PAUSED, obs.timestamp, pausePayload))
                        add(
                            AppEffect.ScheduleTimeout(
                                durationMs,
                                TimeoutType.SESSION_PAUSED_SAFETY,
                                // Route the fire back to THIS region — without it the timeout
                                // steps Platform.Unknown and the pause never expires (#342).
                                platform = next.platform,
                            )
                        )
                        add(AppEffect.UpdateBubble("Dash Paused!"))
                    }
                }
            }
        }
    }

    /**
     * Schedule/cancel the wake-up timer for a [PendingDestructive] grace
     * window (#431). Before this, grace commits were only LAZY — they waited
     * for the next observation, so a session could stay alive in state for
     * hours after going offline with the app backgrounded. The timer routes
     * back to this region (platform-scoped, #342); the stepper's lazy expiry
     * performs the actual commit when the timeout observation arrives.
     * A commit (pending → null with the destructive applied) also lands in
     * the cancel branch — harmless, the timer has already fired or no-ops.
     */
    private fun diffGraceTimer(
        prev: PlatformRegion,
        next: PlatformRegion,
        obs: Observation,
    ): List<AppEffect> {
        val prevPend = prev.pendingDestructive
        val nextPend = next.pendingDestructive
        return when {
            nextPend != null && (prevPend == null || prevPend.deadline != nextPend.deadline) ->
                listOf(
                    AppEffect.ScheduleTimeout(
                        durationMs = (nextPend.deadline - obs.timestamp).coerceAtLeast(1L),
                        type = TimeoutType.GRACE_COMMIT,
                        platform = next.platform,
                    ),
                )
            prevPend != null && nextPend == null ->
                listOf(AppEffect.CancelTimeout(TimeoutType.GRACE_COMMIT, next.platform))
            else -> emptyList()
        }
    }

    /**
     * Schedule/cancel the wake-up timer for a graced screen-implied resume out of
     * Paused (#605) — the [PlatformRegion.pendingModeResume] mirror of
     * [diffGraceTimer]. A SEPARATE [TimeoutType.MODE_RESUME_COMMIT] (not a shared
     * GRACE_COMMIT) because both graces belong to the SAME platform region, so even the
     * (type, platform) timer key (#438 item 1) would not separate them under a shared
     * GRACE_COMMIT — a resume-grace timer would cross-cancel a live destructive grace
     * timer. Arm (or a re-arm with a new deadline) schedules; a cancel (paused frame
     * within the window, or the resume committing) cancels. A commit lands in the
     * cancel branch too — harmless, the timer has already fired or no-ops.
     */
    private fun diffModeResumeTimer(
        prev: PlatformRegion,
        next: PlatformRegion,
        obs: Observation,
    ): List<AppEffect> {
        val prevPend = prev.pendingModeResume
        val nextPend = next.pendingModeResume
        return when {
            nextPend != null && (prevPend == null || prevPend.deadline != nextPend.deadline) ->
                listOf(
                    AppEffect.ScheduleTimeout(
                        durationMs = (nextPend.deadline - obs.timestamp).coerceAtLeast(1L),
                        type = TimeoutType.MODE_RESUME_COMMIT,
                        platform = next.platform,
                    ),
                )
            prevPend != null && nextPend == null ->
                listOf(AppEffect.CancelTimeout(TimeoutType.MODE_RESUME_COMMIT, next.platform))
            else -> emptyList()
        }
    }

    private fun diffTask(
        prev: PlatformRegion,
        next: PlatformRegion,
        obs: Observation,
    ): List<AppEffect> {
        // Diffs are computed from prev/next region state for EVERY observation type:
        // lazy expiry can retire a task on a non-flow observation (a routed timeout,
        // #342), and that closure must still emit DELIVERY_CONFIRMED (#345).
        // #438 B5: the acted-flow reads that drove the (now cross-platform-arbitrated) odometer
        // Pause/Resume were removed here — the task edges below emit only log events + bubbles.
        val sessionId = next.session?.sessionId ?: prev.session?.sessionId

        return buildList {
            val prevTask = prev.activeTask
            val nextTask = next.activeTask

            // #526 D5 sweep: a pickup→pickup active-task change emits NOTHING new here — the new
            // pickup's PICKUP_NAV_STARTED fires in the branch just below, and the displaced pickup's
            // PICKUP_CONFIRMED is now emitted by the CONFIRM SWEEP at the pickup→dropoff edge / the
            // #596 close-out (see [pickupConfirmSweepEffects]). The old per-edge displacement confirm
            // fired a premature confirm on an order-not-ready reroute/flap (then the real confirm was
            // suppressed by the burned per-task key + polluted the #556 shop rate) and a phantom
            // confirm on a D4a swap edge — the sweep replaces all of it.

            // Task started — pickup navigation.
            //
            // Fires whenever a new PICKUP task is the active task — either the
            // first task of the session (prevTask == null) or a new task minted
            // for a stacked-pickup transition (prevTask is the now-completed
            // previous pickup with a different taskId).
            if (nextTask != null &&
                nextTask.phase == TaskPhase.PICKUP &&
                prevTask?.taskId != nextTask.taskId
            ) {
                val taskStartOverride = triggerOverrideEffects(obs, TransitionTrigger.TASK_START)
                if (taskStartOverride != null) {
                    addAll(taskStartOverride)
                } else {
                    val storeName = nextTask.storeName ?: UNKNOWN_STORE
                    val payload = pickupPayload(nextTask, storeName)
                    add(logEffect(sessionId, AppEventType.PICKUP_NAV_STARTED, obs.timestamp, payload))
                    // #438 B5: ResumeOdometer is arbitrated by the cross-platform stationary
                    // predicate (diffCrossPlatform), no longer emitted per pickup-nav edge.

                    val persona = determinePickupPersona(
                        nextTask.activity,
                        nextTask.arrivedAt != null,
                        storeName,
                    )
                    add(AppEffect.UpdateBubble("Pickup: $storeName", persona, dedupeScope = nextTask.taskId))
                }
            }

            // Delivery confirmed: the active task is no longer this dropoff,
            // either because it became null (PostTask / Idle / session end) or
            // because a new task took over (next pickup, next dropoff leg).
            // DoorDash drop-off doesn't surface an explicit "arrived" screen
            // we can rely on, so this transition is the closure signal.
            if (prevTask?.phase == TaskPhase.DROPOFF &&
                (nextTask == null || nextTask.taskId != prevTask.taskId)
            ) {
                val deliveryConfirmed = deliveryPhasePayload(
                    task = prevTask,
                    phaseStartedAt = prevTask.startedAt,
                )
                add(logEffect(sessionId, AppEventType.DELIVERY_CONFIRMED, obs.timestamp, deliveryConfirmed))
            }

            // Task phase changed — pickup → dropoff (pickup confirmed): the
            // FIRST leg of a delivery. The stacked leg-2+ case (dropoff→dropoff /
            // null→dropoff) is handled by the branch just below.
            if (prevTask?.phase == TaskPhase.PICKUP &&
                nextTask?.phase == TaskPhase.DROPOFF
            ) {
                // #526 D5 sweep: confirm EVERY arrived pickup in this job's lineage (the just-
                // displaced prevTask + any earlier displaced pickups), each at its own completion
                // time, BEFORE the dropoff's DELIVERY_NAV_STARTED (CONFIRMED-before-NAV). Per-task
                // keys make this idempotent with the close-out sweep below.
                addAll(pickupConfirmSweepEffects(sessionId, next, prevTask.jobId, obs))
                addAll(deliveryNavStartedEffects(sessionId, nextTask, obs))
            }

            // New dropoff leg — the active task became a DIFFERENT dropoff (#603).
            // The pickup→dropoff branch above only mints DELIVERY_NAV_STARTED on
            // the first leg; a stacked leg-2 (or later) drop arrives as
            // dropoff→dropoff, or as null→dropoff once leg-1 has been
            // grace-retired. Without this the second drop was silent — no nav
            // event, no odometer resume, no "Heading to" bubble.
            // Guards:
            //  - a genuinely new task (taskId changed),
            //  - that started on THIS frame (fresh mint AND placeholder-resolve
            //    both stamp startedAt = obs.timestamp; a RESUMED task keeps its
            //    old startedAt, so a replay/re-sight can't re-fire the nav),
            //  - whose predecessor was NOT a pickup (the pickup→dropoff case is
            //    already handled above — this keeps the two branches disjoint).
            if (nextTask?.phase == TaskPhase.DROPOFF &&
                nextTask.taskId != prevTask?.taskId &&
                nextTask.startedAt == obs.timestamp &&
                prevTask?.phase != TaskPhase.PICKUP
            ) {
                addAll(deliveryNavStartedEffects(sessionId, nextTask, obs))
            }

            // Arrival detection — task subflow changed to ARRIVED
            if (nextTask != null && nextTask.arrivedAt != null &&
                (prevTask?.arrivedAt == null)
            ) {
                val arrivedOverride = triggerOverrideEffects(obs, TransitionTrigger.TASK_ARRIVED)
                if (arrivedOverride != null) {
                    addAll(arrivedOverride)
                } else {
                    // #438 B5: PauseOdometer is arbitrated by the cross-platform stationary
                    // predicate (diffCrossPlatform) — it fires only when ALL live regions are
                    // parked, so one platform's arrival can't pause GPS mid-drive on another's leg.

                    when (nextTask.phase) {
                        TaskPhase.PICKUP -> add(
                            logEffect(
                                sessionId, AppEventType.PICKUP_ARRIVED, obs.timestamp,
                                pickupPayload(
                                    task = nextTask,
                                    storeName = nextTask.storeName ?: UNKNOWN_STORE,
                                ),
                            )
                        )
                        TaskPhase.DROPOFF -> add(
                            logEffect(
                                sessionId, AppEventType.DELIVERY_ARRIVED, obs.timestamp,
                                deliveryPhasePayload(
                                    task = nextTask,
                                    phaseStartedAt = nextTask.startedAt,
                                ),
                            )
                        )
                    }
                }
            }

            // Internal pickup updates — store name, status, etc.
            if (prevTask != null && nextTask != null &&
                prevTask.phase == TaskPhase.PICKUP &&
                nextTask.phase == TaskPhase.PICKUP
            ) {
                val prevName = prevTask.storeName?.trim()
                val nextName = nextTask.storeName?.trim()
                val storeChanged = nextName != prevName &&
                    nextName != null && nextName != UNKNOWN_STORE
                val activityChanged = nextTask.activity != prevTask.activity

                if (storeChanged || activityChanged) {
                    val storeName = nextTask.storeName ?: UNKNOWN_STORE
                    val persona = determinePickupPersona(
                        nextTask.activity,
                        nextTask.arrivedAt != null,
                        storeName,
                    )
                    add(AppEffect.UpdateBubble("Pickup: $storeName", persona, dedupeScope = nextTask.taskId))
                }

                // Store name resolution — re-emit the pickup payload with the
                // updated store name. The mapper treats the latest
                // PICKUP_NAV_STARTED per task as canonical, so this is the
                // store name the Pickup card will render.
                if (storeChanged) {
                    val storeName = nextTask.storeName ?: UNKNOWN_STORE
                    add(
                        logEffect(
                            sessionId, AppEventType.PICKUP_NAV_STARTED, obs.timestamp,
                            pickupPayload(nextTask, storeName),
                        )
                    )
                }
            }

            // #438 B5: the PostTask-entry ResumeOdometer moved to the cross-platform stationary
            // predicate (diffCrossPlatform). Leaving an arrived drop for PostTask is a
            // stationary→moving crossing there; entering PostTask from a non-arrived drive was a
            // redundant no-op Resume (GPS already running) and is correctly elided.
        }
    }

    /**
     * The "delivery nav started" effect trio emitted when a NEW dropoff task
     * becomes the active task: the DELIVERY_NAV_STARTED log event (phase clock
     * stamped to this frame), the odometer resume, and the store-flavored
     * "Heading to <customer>" bubble. Shared by the first-leg pickup→dropoff
     * transition and the stacked leg-2 dropoff→dropoff / null→dropoff transition
     * (#603) so a stacked drop gets the exact same driver-facing treatment as
     * the first — one silent-second-drop bug, not two code paths that can drift.
     *
     * [task]'s storeName may be null on a leg-2 drop the dropoff screen didn't
     * carry a store for; [customerLabel] then falls back to the generic
     * recipient (never the hash). Leg-2 store re-attribution is #526's scope.
     */
    private fun deliveryNavStartedEffects(
        sessionId: String?,
        task: Task,
        obs: Observation,
    ): List<AppEffect> = buildList {
        add(
            logEffect(
                sessionId,
                AppEventType.DELIVERY_NAV_STARTED,
                obs.timestamp,
                deliveryPhasePayload(task = task, phaseStartedAt = obs.timestamp),
            ),
        )
        // #438 B5: ResumeOdometer arbitrated by the cross-platform stationary predicate
        // (diffCrossPlatform) — leaving an arrived pickup/drop for the next leg is the
        // stationary→moving crossing that resumes GPS there.
        // #568: store-flavored label ("Heading to H-E-B's customer") — friendlier
        // than the raw 6-char hash, and it disambiguates a multi-store stack's drops.
        val customer = customerLabel(task.storeName)
        add(AppEffect.UpdateBubble("Heading to $customer", ChatPersona.Customer(customer), dedupeScope = task.taskId))
    }

    /**
     * #526 D5 sweep: confirm every ARRIVED pickup in [jobId]'s lineage as visible to EffectMap —
     * the job's completed/displaced pickups in [PlatformRegion.recentTasks] plus the active task if
     * it is still a pickup of this job. Each emits one `PICKUP_CONFIRMED` keyed on its task id, so
     * the two sweep sites (the pickup→dropoff edge AND the #596 close-out) are idempotent under the
     * engine's effects_fired dedup. Each task confirms at its OWN completion time ([Task.completedAt],
     * the real confirm moment) — a still-active edge task, not yet displaced, falls back to
     * `obs.timestamp`. Replaces the old pickup→pickup displacement confirm.
     */
    private fun pickupConfirmSweepEffects(
        sessionId: String?,
        region: PlatformRegion,
        jobId: String?,
        obs: Observation,
    ): List<AppEffect> = buildList {
        if (jobId == null) return@buildList
        val lineage = (region.recentTasks + listOfNotNull(region.activeTask))
            .filter { it.jobId == jobId && it.phase == TaskPhase.PICKUP && it.arrivedAt != null }
            .distinctBy { it.taskId }
        for (task in lineage) {
            addAll(pickupConfirmedEffects(sessionId, task, obs, confirmedAt = task.completedAt ?: obs.timestamp))
        }
    }

    /**
     * #526 D5/D5a: the effects for confirming a completed PICKUP leg — `PICKUP_CONFIRMED` keyed on
     * the confirmed task's id (so an A→B→A resume then A→dropoff can't double-confirm the same
     * pickup under mixed keying), plus the #556 [AppEffect.RecordShopRate] rider when it was a shop.
     * [confirmedAt] is the real confirm time (the swept task's `completedAt`, or `obs.timestamp` at
     * the edge) — it stamps the log event AND the shop-rate window (arrived→confirmed).
     */
    private fun pickupConfirmedEffects(
        sessionId: String?,
        prevTask: Task,
        obs: Observation,
        confirmedAt: Long = obs.timestamp,
    ): List<AppEffect> = buildList {
        add(
            logEffect(
                sessionId,
                AppEventType.PICKUP_CONFIRMED,
                confirmedAt,
                pickupPayload(task = prevTask, storeName = prevTask.storeName ?: UNKNOWN_STORE, confirmedAt = confirmedAt),
                effectKeyOverride = "log:${AppEventType.PICKUP_CONFIRMED}:${prevTask.taskId}",
            ),
        )
        // #556: a completed SHOP pickup feeds the learned items/min. In-store time is measured
        // arrived→confirmed (the 0.8/min seed basis); the handler floors out noise.
        val shopItems = prevTask.itemsShopped ?: 0
        val shopArrivedAt = prevTask.arrivedAt
        if (prevTask.activity == PickupActivity.SHOPPING && shopItems > 0 && shopArrivedAt != null) {
            add(
                AppEffect.RecordShopRate(
                    itemsShopped = shopItems,
                    shopDurationMs = confirmedAt - shopArrivedAt,
                    storeName = prevTask.storeName,
                    jobId = prevTask.jobId,
                    taskId = prevTask.taskId,
                ),
            )
        }
    }

    /**
     * Handle PostTask effects while on the PostTask screen.
     *
     * Emits at most ONE "Saved: $X" bubble per delivery, gated by the
     * per-task idempotency field `lastAnnouncedPostTaskTaskId` (which the
     * stepper stamps with the current taskId on every PostTaskFields
     * observation). The bubble uses whatever shape is available on first
     * sighting:
     *  - parsedPay present (expanded) → full receipt with line items
     *  - parsedPay null (collapsed) → minimal "Saved: $X"
     *
     * If the breakdown arrives later (e.g. auto-expand succeeds after a
     * collapsed-first sighting), it's still captured in `lastPostTaskFields`
     * by the stepper and surfaces via the post-task card in the bubble HUD
     * + the DELIVERY_COMPLETED payload. But no second bubble fires.
     *
     * DELIVERY_COMPLETED itself is emitted from [diffPlatformRegion] when
     * leaving PostTask (with the full pay breakdown if it was ever captured).
     */
    private fun diffPostTask(
        prev: PlatformRegion,
        next: PlatformRegion,
        actedNextFlow: Flow,
        obs: Observation,
    ): List<AppEffect> {
        // #438 item 5: gate on THIS region's own acted flow — the obs is global, so without this a
        // non-observing region would also fire the "Saved: $X" bubble on the owner's PostTask frame.
        if (actedNextFlow != Flow.PostTask) return emptyList()
        val flowObs = obs as? Observation.FlowObservation ?: return emptyList()
        val parsed = flowObs.parsed as? ParsedFields.PostTaskFields ?: return emptyList()

        // The completing task stays ACTIVE while its retire grace is pending
        // (#431 pt 2) — resolve it first, falling back to the last committed
        // task. Must mirror the stepper's lastAnnouncedPostTaskTaskId stamp.
        val taskId = next.activeTask?.taskId
            ?: next.recentTasks.lastOrNull()?.taskId
            ?: return emptyList()
        if (prev.lastAnnouncedPostTaskTaskId == taskId) return emptyList()
        if (parsed.totalPay <= 0) return emptyList()

        val payData = parsed.parsedPay
        val text = if (payData != null) {
            buildString {
                append("Saved: ${Formats.money(payData.total)}")
                payData.customerTips.forEach { item ->
                    append("\nTip: ${item.displayLabel} • ${Formats.money(item.amount)}")
                }
            }
        } else {
            "Saved: ${Formats.money(parsed.totalPay)}"
        }
        return listOf(AppEffect.UpdateBubble(text, ChatPersona.Earnings))
    }

    /**
     * Handle notification-driven effects. These are global interceptors
     * that apply regardless of state.
     */
    /**
     * Intent-specific notification processing that can't be expressed as
     * a JSON effect. Logging and other simple effects are now declared in
     * the rule JSON and handled by [diffRuleEffects].
     */
    private fun diffNotification(obs: Observation): List<AppEffect> {
        if (obs !is Observation.Notification) return emptyList()
        val fields = obs.parsed as? ParsedFields.NotificationFields ?: return emptyList()

        return buildList {
            when (fields.intent) {
                "additional_tip" -> {
                    val amount = fields.amount
                    val storeName = fields.storeName
                    val deliveredAt = fields.deliveredAt
                    if (amount != null && storeName != null && deliveredAt != null) {
                        add(
                            AppEffect.ProcessTipNotification(
                                amount = amount,
                                storeName = storeName,
                                deliveredAt = deliveredAt,
                            )
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // TRANSITION OVERRIDE CHECK
    // =========================================================================

    /**
     * Check if the observation carries a rule-declared override for [trigger].
     * When present, returns [AppEffect.RequestEffect] for each override effect
     * (replacing the built-in defaults). Returns null when no override exists
     * (caller falls through to defaults).
     */
    private fun triggerOverrideEffects(
        obs: Observation,
        trigger: TransitionTrigger,
    ): List<AppEffect>? {
        val flowObs = obs as? Observation.FlowObservation ?: return null
        val overrides = flowObs.transitionOverrides[trigger] ?: return null
        return overrides
            .filter { evaluateGate(it.onlyIf, flowObs.parsed) }
            .map { AppEffect.RequestEffect(it) }
    }

    // =========================================================================
    // RULE-ORIGINATED EFFECTS
    // =========================================================================

    /**
     * Extract rule-declared effects from the observation and emit
     * [AppEffect.RequestEffect] for each that passes its gate.
     * Runs at top level — NOT inside any region stepper. All rule effects
     * are observational/app-internal (#425) — actuation never rides here.
     *
     * A [Observation.Notification] is a discrete arrival, not an
     * install-once fact (#604): its effects get `keySuffix = timestamp`
     * (postTime — event time, replay-stable) so each arrival keys its own
     * `effects_fired` row instead of every notification of that intent
     * colliding on one global key. Screens keep `keySuffix = null` — their
     * cross-frame dedup (e.g. `offer-ss-{parsedHash}`) is intended and must
     * not be disturbed.
     */
    private fun diffRuleEffects(obs: Observation): List<AppEffect> {
        val flowObs = obs as? Observation.FlowObservation ?: return emptyList()
        if (flowObs.effects.isEmpty()) return emptyList()
        val keySuffix = (obs as? Observation.Notification)?.timestamp?.toString()
        return flowObs.effects
            .filter { evaluateGate(it.onlyIf, flowObs.parsed) }
            .map { AppEffect.RequestEffect(it, keySuffix = keySuffix) }
    }

    /**
     * App-owned EXPAND_EARNINGS decision (#425): when the post-delivery
     * summary is collapsed and the rule bound an expand target, schedule the
     * tap behind a SETTLE_UI timeout so the screen finishes animating first
     * (observable + cancellable, unlike a hidden sleep). Replaces the rule's
     * former `click` effect — the decision is the app's, the target is data.
     */
    private fun diffExpandAction(obs: Observation): List<AppEffect> {
        val flowObs = obs as? Observation.Screen ?: return emptyList()
        val action = RuleAction.EXPAND_EARNINGS
        val target = flowObs.targets[action.targetBindName] ?: return emptyList()
        // Only when the screen itself reports the breakdown collapsed — the
        // gate fails closed on an absent/unparseable field (#345).
        val collapsed = evaluateGate(
            ParsedFieldsGate.FieldEquals("isExpanded", false), flowObs.parsed,
        )
        if (!collapsed) return emptyList()
        return listOf(
            AppEffect.ScheduleTimeout(
                durationMs = graceConfig.forPlatform(flowObs.platform).expandSettleMs,
                type = TimeoutType.SETTLE_UI,
                platform = flowObs.platform.takeIf { it != Platform.Unknown },
                payload = ObservationPayload.DeferredAction(
                    action = action.wire,
                    platform = flowObs.platform.wire,
                    ruleId = flowObs.ruleId,
                    target = target,
                ),
            ),
        )
    }

    /**
     * #577 quick-decline: when DoorDash's confirm-decline dialog appears during an active offer and
     * the rule bound a confirm button, schedule the app-owned [RuleAction.CONFIRM_DECLINE] tap behind
     * the same SETTLE_UI delay as [diffExpandAction] — the dialog animates in and ~half the captured
     * confirm frames are transitional, so the settle wait lets the button render. PURE: the dasher's
     * *consent* is the `quickDeclinesEnabled` setting, enforced at the engine edge ([SideEffectEngine]
     * denies the AUTOMATION fire when off) — here we only emit the deferred intent when the screen +
     * offer context match. The fire is label-verified ("decline") + package-scoped; a missing/garbage
     * target fails closed (the dasher confirms manually). The bind exists only on the
     * confirm-decline rule, so `targets[...]` is the screen gate; `pendingOffer` confirms a live offer.
     */
    private fun diffConfirmDeclineAction(obs: Observation, next: AppState): List<AppEffect> {
        val flowObs = obs as? Observation.Screen ?: return emptyList()
        val action = RuleAction.CONFIRM_DECLINE
        val target = flowObs.targets[action.targetBindName] ?: return emptyList()
        // #438 B3 (vet M3): a live offer is now the OBSERVING platform's own presented offer, not the
        // shared global R0 slot — re-keyed off `pendingOffers`, or auto-confirm silently dies.
        val hasLiveOffer = next.regions.platforms[flowObs.platform]?.presentedOffer() != null
        if (!hasLiveOffer) return emptyList()
        return listOf(
            AppEffect.ScheduleTimeout(
                durationMs = graceConfig.forPlatform(flowObs.platform).expandSettleMs,
                type = TimeoutType.SETTLE_UI,
                platform = flowObs.platform.takeIf { it != Platform.Unknown },
                payload = ObservationPayload.DeferredAction(
                    action = action.wire,
                    platform = flowObs.platform.wire,
                    ruleId = flowObs.ruleId,
                    target = target,
                ),
            ),
        )
    }

    /**
     * Catch the SETTLE_UI timeout fired by a deferred action (see
     * [diffExpandAction]) and emit the immediate-fire [AppEffect.PerformRuleAction].
     */
    private fun diffSettleUiTimeout(obs: Observation): List<AppEffect> {
        val timeout = obs as? Observation.Timeout ?: return emptyList()
        if (timeout.type != TimeoutType.SETTLE_UI) return emptyList()
        // Typed payload (#366) — the old per-key Map round-trip is gone.
        val deferred = timeout.payload as? ObservationPayload.DeferredAction ?: return emptyList()
        val action = RuleAction.fromWire(deferred.action) ?: return emptyList()
        val platform = Platform.fromWire(deferred.platform) ?: return emptyList()
        return listOf(
            AppEffect.PerformRuleAction(
                action = action,
                platform = platform,
                targetRef = deferred.target,
                sourceRuleId = deferred.ruleId,
                // The app decided this tap on its own — it must be covered by
                // a granted capability at the engine's consent gate (#417).
                trigger = ActionTrigger.AUTOMATION,
            ),
        )
    }

    private fun evaluateGate(gate: ParsedFieldsGate?, parsed: ParsedFields): Boolean {
        if (gate == null) return true
        // Explicit per-subtype field maps (#434) replace the old Java
        // reflection here — exhaustive over the sealed hierarchy, so
        // extraction can never fail, and rename-proof under R8. The #345
        // fail-closed posture is preserved by the absent-field semantics
        // below: a field the subtype doesn't carry proves nothing.
        val fieldsMap = parsed.toFieldMap()
        return when (gate) {
            is ParsedFieldsGate.FieldEquals -> fieldsMap[gate.field] == gate.value
            // An ABSENT field (wrong name, or ParsedFields.None) proves nothing —
            // only a present-but-different value satisfies "not equals".
            is ParsedFieldsGate.FieldNotEquals ->
                gate.field in fieldsMap && fieldsMap[gate.field] != gate.value
            is ParsedFieldsGate.FieldNotNull -> fieldsMap[gate.field] != null
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    internal fun resolveOfferOutcome(obs: Observation, prevOffer: PendingOffer? = null): AppEventType {
        // 0. Decline-commit latch (#594): a DECLINE-intent click already committed this offer's
        //    decline server-side. That decision is final — a later "Review offer"→Accept click
        //    cannot un-decline it — so the latch wins over lastClickIntent AND the direct-click
        //    fallback below.
        if (prevOffer?.declineCommittedAt != null) return AppEventType.OFFER_DECLINED
        // 1. Stored click intent on PendingOffer — covers the common case where
        //    the click was observed first and the resolving obs is a Screen. The
        //    ACCEPT arm routes through the shared #526 D1b predicate (the SSOT the
        //    accept-stash arming also uses); DECLINE stays explicit here.
        if (prevOffer != null && prevOffer.isAcceptLatched()) return AppEventType.OFFER_ACCEPTED
        if (prevOffer?.lastClickIntent == OfferIntent.DECLINE) return AppEventType.OFFER_DECLINED
        // 2. Direct click observation — covers the edge case where click and
        //    flow change arrive in the same observation
        val clickFields = when (obs) {
            is Observation.Click -> obs.parsed as? ParsedFields.ClickFields
            else -> null
        }
        return when (clickFields?.intent) {
            OfferIntent.ACCEPT -> AppEventType.OFFER_ACCEPTED
            OfferIntent.DECLINE -> AppEventType.OFFER_DECLINED
            else -> AppEventType.OFFER_TIMEOUT
        }
    }

    /**
     * #601: the SSOT for what an offer outcome card says. Both the resolution-block card
     * (the offer that just pop'd) and the replaced-offer card (the OLD offer, suffixed by the
     * caller) route through this ONE table, keyed on the exact [AppEventType] that gets logged
     * to the ledger — so the chat can never claim an outcome the ledger doesn't record.
     */
    internal fun outcomeCardText(outcome: AppEventType): String = when (outcome) {
        AppEventType.OFFER_ACCEPTED -> "Offer Accepted"
        AppEventType.OFFER_DECLINED -> "Offer Declined"
        AppEventType.OFFER_TIMEOUT -> "Offer Timed Out!"
        // Fail OPEN on display text, never on the reducer loop (#625 review): EffectMap
        // is diffed inside StateManagerV2's event loop, which has no try/catch — a throw
        // here would freeze the state machine and let the unbounded observation buffer
        // grow until restart. A neutral string is a harmless card; today the only caller
        // passes resolveOfferOutcome output, so this is a future-proofing floor.
        else -> {
            Timber.e("outcomeCardText got a non-outcome type: %s — using neutral text", outcome)
            "Offer resolved"
        }
    }

    private fun determinePickupPersona(
        activity: String?,
        arrived: Boolean,
        storeName: String,
    ): ChatPersona {
        return when {
            activity == PickupActivity.SHOPPING -> ChatPersona.Shopper
            // #568: store-flavored, never the raw hash (keeps the "never show the hash" invariant
            // literal). customerHash stays the identity key; storeName drives display.
            activity == PickupActivity.CONFIRMED -> ChatPersona.Customer(customerLabel(storeName))
            arrived -> ChatPersona.Merchant(storeName)
            else -> ChatPersona.Navigator
        }
    }

    // Pure domain emission (#354): payload encoding + device metadata happen at the
    // executor edge. occurredAt is the OBSERVATION timestamp, so the LogEvent's
    // idempotency key is identical between live execution and recovery replay (#300).
    internal fun logEffect(
        sessionId: String?,
        type: AppEventType,
        occurredAt: Long,
        payload: AppEventPayload?,
        effectKeyOverride: String? = null,
    ): AppEffect = AppEffect.LogEvent(
        AppEvent(
            type = type,
            occurredAt = occurredAt,
            sessionId = sessionId,
            payload = payload,
        ),
        effectKeyOverride = effectKeyOverride,
    )

    // =========================================================================
    // PAYLOAD BUILDERS
    //
    // Build rich phase-boundary payloads from in-memory state. Same emit
    // moments as before — richer payload each one writes. The flow-card
    // mapper folds these into per-phase snapshots without joining other
    // entities; see #257.
    // =========================================================================

    internal fun offerPayload(
        offer: PendingOffer,
        outcome: AppEventType,
        decidedAt: Long,
        description: String? = null,
    ): OfferPayload = OfferPayload(
        offerHash = offer.offerHash,
        parsedOffer = offer.offerFields.parsedOffer,
        evaluation = offer.evaluation,
        outcome = outcome,
        presentedAt = offer.presentedAt,
        decidedAt = decidedAt,
        returnFlow = offer.returnFlow,
        description = description,
    )

    private fun pickupPayload(
        task: Task,
        storeName: String,
        confirmedAt: Long? = null,
    ): PickupPayload = PickupPayload(
        jobId = task.jobId,
        taskId = task.taskId,
        storeName = storeName,
        phaseStartedAt = task.startedAt,
        arrivedAt = task.arrivedAt,
        confirmedAt = confirmedAt,
        odometerAtEntry = task.odometerAtEntry,
        odometerAtArrival = task.odometerAtArrival,
        deadlineMillis = task.deadlineMillis,
        itemsRemaining = task.itemsRemaining,
        itemsShopped = task.itemsShopped,
        redCardTotal = task.redCardTotal,
        activity = task.activity,
    )

    private fun deliveryPhasePayload(
        task: Task,
        phaseStartedAt: Long,
    ): DeliveryPayload = DeliveryPayload(
        jobId = task.jobId,
        taskId = task.taskId,
        storeName = task.storeName,
        customerHash = task.customerNameHash,
        addressHash = task.customerAddressHash,
        phaseStartedAt = phaseStartedAt,
        arrivedAt = task.arrivedAt,
        odometerAtEntry = task.odometerAtEntry,
        odometerAtArrival = task.odometerAtArrival,
        deadlineMillis = task.deadlineMillis,
    )

    private fun deliveryCompletedPayload(
        task: Task?,
        jobId: String?,
        completedAt: Long,
        postTaskFields: ParsedFields.PostTaskFields?,
        sessionEarnings: Double?,
        dropRealizedPay: Double? = null,
        offerPayShare: Double? = null,
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
        offerPayShare = offerPayShare,
        sessionEarningsAtCompletion = sessionEarnings,
    )

    /**
     * #691 offer-pay estimate share for [taskId] of [job] when the job was WHOLLY receipt-less — the
     * write-side stamp that lets a receipt-less shop delivery fold a real net row instead of a
     * $0-unattributed one. Thin edge over the pure [OfferPayFallback] policy: this computes the two
     * inputs only the region can see — the job-scoped receipt-evidence verdict
     * ([receiptSuppressesEstimate]) and the per-site final-shape flag — then delegates the eligibility
     * + split, and owns the observability WARN (FIX 6) for an eligible-but-unsplit drop.
     *
     * [requireFinalShape] is true at the PostTask-exit mint (job may still be open — stamp only the
     * LAST OPEN owed drop) and false at the #596 close-out (job already closed → final shape).
     */
    private fun offerPayShareFor(
        region: PlatformRegion,
        job: Job?,
        taskId: String,
        requireFinalShape: Boolean,
    ): Double? {
        if (job == null) return null
        val result = OfferPayFallback.shareFor(
            job = job,
            mintingTaskId = taskId,
            suppressedByReceipt = receiptSuppressesEstimate(region, job),
            requireFinalShape = requireFinalShape,
        )
        if (result.eligibleButUnsplit) {
            // The drop is estimate-ELIGIBLE (receipt-less, final shape) yet got NO share — a pay-less
            // offer or a minting task outside the owed set (the quoted>delivered halving class). WARN
            // it so the silent-denominator miss is observable. PII-safe: counts + jobId only, no
            // store/customer text, stable tag (Principle 7; the #699 D6 join-miss precedent).
            Timber.tag("StateMachine").w(
                "#691 offer-pay estimate eligible but unsplit: job %s, %d owed dropoffs, offerTotal=%s " +
                    "— no share stamped; these dollars ride the unattributed bucket",
                job.jobId,
                job.tasks.count { it.phase == TaskPhase.DROPOFF },
                if (job.offerPayTotal == null) "null" else "present",
            )
        }
        return result.share
    }

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
    private fun receiptSuppressesEstimate(region: PlatformRegion, job: Job): Boolean {
        val fields = region.lastPostTaskFields ?: return false
        val payBearing = fields.parsedPay != null || fields.totalPay > 0.0
        if (!payBearing) return false
        val announceId = region.lastAnnouncedPostTaskTaskId ?: return true // null → conservative suppress
        if (job.tasks.any { it.taskId == announceId }) return true // this job's receipt → suppress
        val regionTasks = region.recentTasks + listOfNotNull(region.activeTask)
        val provablyAnotherJob = regionTasks.any { it.taskId == announceId && it.jobId != job.jobId }
        return !provablyAnotherJob // unknown id → conservative suppress; foreign id → do not suppress
    }

    /**
     * The job's delivered dropoff tasks — the completion rows the [DropPayApportioner] splits the
     * receipt across (#528). Sourced from the region records at the mint step (`recentTasks` +
     * the active task, deduped by id), scoped to [jobId] and the DROPOFF phase, and restricted to
     * identity-bearing drops so it mirrors the close-out's #498 identity firewall — an identity-
     * less phantom that never mints a completion must not inflate the split denominator.
     */
    private fun jobDropoffTasks(region: PlatformRegion, jobId: String?): List<Task> {
        if (jobId == null) return emptyList()
        return (region.recentTasks + listOfNotNull(region.activeTask))
            .filter {
                it.jobId == jobId &&
                    it.phase == TaskPhase.DROPOFF &&
                    (it.customerNameHash != null || it.customerAddressHash != null)
            }
            .distinctBy { it.taskId }
    }
}
