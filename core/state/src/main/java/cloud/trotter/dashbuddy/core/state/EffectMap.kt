package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.action.ActionTrigger
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.config.EvidenceCategory
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
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ParsedFieldsGate
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
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
class EffectMap @Inject constructor() {

    companion object {
        /**
         * Safety buffer added to the platform's reported pause countdown
         * before scheduling the offline timeout. Accounts for clock skew
         * between the parsed timestamp and the actual timer start.
         */
        const val PAUSE_TIMEOUT_BUFFER_MS = 1000L

        /**
         * Settle delay before the EXPAND_EARNINGS tap (#425) — lets the
         * post-delivery summary finish animating so the bound chevron's
         * fingerprint still matches what gets tapped. Carried over from the
         * former rule-declared click's `delayMs`.
         */
        const val EXPAND_SETTLE_MS = 500L
    }


    fun diff(prev: AppState, next: AppState, obs: Observation): List<AppEffect> = buildList {
        addAll(diffRuleEffects(obs))
        addAll(diffExpandAction(obs))
        addAll(diffConfirmDeclineAction(obs, next))
        addAll(diffSettleUiTimeout(obs))
        // Offer-resolution events fire in the FlowRegion handler. They need to
        // be scoped to the active platform's session so AppEventDao queries
        // by dashId see them — without this, the bubble HUD's card stack
        // never sees offer events (#257).
        val activeSessionId = next.regions.flow.activePlatform
            ?.let { next.regions.platforms[it]?.session?.sessionId }
            ?: prev.regions.flow.activePlatform
                ?.let { prev.regions.platforms[it]?.session?.sessionId }
        addAll(diffFlowRegion(prev.regions.flow, next.regions.flow, obs, activeSessionId))
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
    // FLOW REGION DIFFS
    // =========================================================================

    private fun diffFlowRegion(
        prev: FlowRegion,
        next: FlowRegion,
        obs: Observation,
        sessionId: String?,
    ): List<AppEffect> = buildList {
        val flowObs = obs as? Observation.FlowObservation
        val prevOffer = prev.pendingOffer
        val nextOffer = next.pendingOffer

        // Offer presented
        // Screenshot for the offer screen still comes from rule-declared
        // effects (deduped via dedupeKey + throttleMs). DB-persisted
        // events flow through here so payloads stay typed and consistent
        // across platforms (#257 design discussion).
        if (prevOffer == null && nextOffer != null) {
            val offer = nextOffer.offerFields
            val platform = (next.activePlatform ?: Platform.Unknown).name

            // Log OFFER_RECEIVED with the full parsed offer. Evaluation
            // hasn't run yet at this point (it fires async via the
            // EvaluateOffer side effect); the rich evaluation lands on
            // the closing OFFER_ACCEPTED / DECLINED / TIMEOUT payload.
            val receivedPayload = OfferReceivedPayload(
                offerHash = nextOffer.offerHash,
                parsedOffer = offer.parsedOffer,
                presentedAt = nextOffer.presentedAt,
                platform = platform,
                returnFlow = nextOffer.returnFlow,
            )
            add(logEffect(sessionId, AppEventType.OFFER_RECEIVED, obs.timestamp, receivedPayload))

            // Evaluate (the heads-up notification + spoken read fire later, once the async
            // evaluation lands on the pending offer — see the eval-landing block below).
            add(AppEffect.EvaluateOffer(offer.parsedOffer, nextOffer.offerHash))
        }

        // Offer replaced (different hash)
        // Screenshot + log for new offer handled by rule-declared effects (deduped
        // per offerHash). Resolution log for old offer stays here.
        if (prevOffer != null && nextOffer != null &&
            prevOffer.offerHash != nextOffer.offerHash
        ) {
            // Log resolution of old offer with full context.
            val outcome = resolveOfferOutcome(obs, prevOffer)
            add(logEffect(sessionId, outcome, obs.timestamp, offerPayload(prevOffer, outcome, obs.timestamp, "Replaced by new offer")))

            // #457: dismiss the OLD offer's heads-up now. Its Accept/Decline is a separate, persistent
            // notification (not the self-replacing bubble), so without this the prior banner lingers
            // until the new offer's async eval lands — and a tap in that window would resolve against
            // the NEW pending offer. The new offer's heads-up re-posts on eval-landing below.
            add(AppEffect.CancelOfferNotification(prevOffer.offerHash))

            // Evaluate the new offer (notification + spoken read fire on eval-landing below).
            val offer = nextOffer.offerFields
            add(AppEffect.EvaluateOffer(offer.parsedOffer, nextOffer.offerHash))
        }

        // Evaluation landed (async loopback) → fire the offer's UI side-effects: the heads-up
        // notification and the spoken read, both off the evaluation. Keyed on the evaluation
        // arriving in state (same offer, eval null → non-null) rather than fired inline from the
        // EvaluateOffer handler, so the offer's UI effects stay first-class + testable.
        val landedEval = nextOffer?.evaluation
        if (prevOffer != null && nextOffer != null && landedEval != null &&
            prevOffer.offerHash == nextOffer.offerHash &&
            prevOffer.evaluation == null
        ) {
            // #578: assemble the rich offer card via the same SSOT the bubble card uses
            // (FlowCardSnapshot.Offer.from), so the heads-up notification renders the same data.
            val parsedOffer = nextOffer.offerFields.parsedOffer
            val expiresAt = parsedOffer.initialCountdownSeconds?.let { nextOffer.presentedAt + it * 1000L }
            val offerCard = FlowCardSnapshot.Offer.from(
                parsedOffer = parsedOffer,
                evaluation = landedEval,
                offerHash = nextOffer.offerHash,
                phaseStartedAt = nextOffer.presentedAt,
                expiresAt = expiresAt,
                countdownSeconds = parsedOffer.initialCountdownSeconds,
            )
            add(AppEffect.PostOfferNotification(landedEval, offerCard, nextOffer.offerHash))
            add(AppEffect.SpeakOffer(landedEval))
        }

        // Offer resolved (accepted/declined/timeout)
        if (prevOffer != null && nextOffer == null) {
            val outcome = resolveOfferOutcome(obs, prevOffer)
            // #594: when the latch forced DECLINED but the last literal click was an ACCEPT, the
            // dasher hit "Review offer"→Accept after the decline was already committed — record
            // that the decline stood, for the forensic payload.
            val raceDescription = if (
                outcome == AppEventType.OFFER_DECLINED &&
                prevOffer.declineCommittedAt != null &&
                prevOffer.lastClickIntent == OfferIntent.ACCEPT
            ) {
                "Accept clicked after decline was already committed — decline stands (#594)"
            } else {
                null
            }
            // Abort a notification still waiting out its post delay (#436) —
            // an Accept/Decline heads-up must not land after the offer is gone.
            add(AppEffect.CancelOfferNotification(prevOffer.offerHash))
            add(logEffect(sessionId, outcome, obs.timestamp, offerPayload(prevOffer, outcome, obs.timestamp, raceDescription)))

            if (outcome == AppEventType.OFFER_TIMEOUT) {
                add(AppEffect.UpdateBubble("Offer Timed Out!", persona = ChatPersona.Dispatcher))
            }
        }

        // Click feedback for offer accept/decline
        if (flowObs is Observation.Click && prev.flow == Flow.OfferPresented) {
            val fields = flowObs.parsed as? ParsedFields.ClickFields
            // #594: the decline-commit latch set on a prior confirm click (survives on this
            // click's next.pendingOffer, or prev's if the pop is concurrent).
            val declineCommitted = next.pendingOffer?.declineCommittedAt ?: prevOffer?.declineCommittedAt
            when (fields?.intent) {
                OfferIntent.ACCEPT ->
                    if (declineCommitted != null) {
                        // The decline was already committed server-side; this Accept (the
                        // "Review offer"→Accept race) won't take. Say so instead of the
                        // contradictory "Offer Accepted", and count the defended invariant
                        // (Principle 7: WARN = a defended invariant fired; no PII in the line).
                        Timber.w(
                            "Accept click ignored (#594): decline already committed at %d — decline stands",
                            declineCommitted,
                        )
                        add(
                            AppEffect.UpdateBubble(
                                "Decline already submitted — Accept won't take",
                                persona = ChatPersona.Dispatcher,
                            )
                        )
                    } else {
                        add(AppEffect.UpdateBubble("Offer Accepted", persona = ChatPersona.Dispatcher))
                    }
                OfferIntent.DECLINE -> add(
                    AppEffect.UpdateBubble("Offer Declined", persona = ChatPersona.Dispatcher)
                )
            }
        }

        // HUD-initiated accept/decline (bubble buttons / own-notification actions) →
        // perform the app-owned action, aimed by the target the offer rule bound
        // (#425). No binding = action unavailable on this platform (fail to
        // manual). Decline taps the initial decline button; in Native mode the
        // user confirms in DoorDash's own dialog (auto-confirm = #110 2c).
        //
        // #457 instrumentation: the notification-SHADE Accept/Decline buttons
        // were found broken in the field while the in-bubble ones work — yet
        // both dispatch this IDENTICAL UiInput, so the divergence is here or
        // downstream. A UiInput never changes the flow (it isn't a
        // FlowObservation), so this gate is purely "was R0 OfferPresented when
        // the tap dispatched" — and a heads-up notification can outlive the
        // on-screen offer. Every drop reason now logs (the gate-skip and the
        // null platform/offer cases were previously silent) so the next field
        // OR chance-desk occurrence self-documents which gate ate the tap, no
        // live logcat required. Behavior is unchanged — only logging is added.
        if (obs is Observation.UiInput) {
            val action = when (obs.action) {
                OfferIntent.ACCEPT -> RuleAction.ACCEPT_OFFER
                OfferIntent.DECLINE -> RuleAction.DECLINE_OFFER
                else -> null
            }
            if (action != null) {
                val onOfferFlow = next.flow == Flow.OfferPresented || prev.flow == Flow.OfferPresented
                val platform = next.activePlatform ?: prev.activePlatform
                val offer = next.pendingOffer ?: prev.pendingOffer
                val target = offer?.targets?.get(action.targetBindName)
                when {
                    !onOfferFlow -> Timber.w(
                        "Offer action %s dropped (#457): R0 flow is %s, not OfferPresented — " +
                            "shade tap likely arrived after the offer left the screen",
                        action.wire, next.flow,
                    )
                    platform == null || offer == null -> Timber.w(
                        "Offer action %s dropped (#457): no active platform/pending offer " +
                            "(platform=%s, offerHash=%s)",
                        action.wire, platform?.wire, offer?.offerHash,
                    )
                    target == null -> Timber.w(
                        "No '%s' target bound for %s — %s unavailable, leaving it to the user (#457)",
                        action.targetBindName, platform.wire, action.wire,
                    )
                    else -> {
                        // USER trigger: the dasher pressed Accept/Decline — that
                        // press is the consent for this fire (#417).
                        Timber.i("Offer action %s firing on %s (#457)", action.wire, platform.wire)
                        add(
                            AppEffect.PerformRuleAction(
                                action, platform, target, offer.sourceRuleId,
                                trigger = ActionTrigger.USER,
                            ),
                        )
                    }
                }
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

        return buildList {
            addAll(diffMode(p, next, obs))
            addAll(diffGraceTimer(p, next, obs))
            addAll(diffTask(p, next, prevFlow, nextFlow, obs))
            addAll(diffPostTask(p, next, nextFlow, obs))
            addAll(diffNotification(obs))

            // Delivery completed: leaving PostTask for a non-PostTask flow.
            //
            // `diff` iterates over all platforms, but `nextFlow.flow` is global.
            // On PostTask exit the condition fires for every platform that has a
            // PlatformRegion entry; only the one that actually owned the delivery
            // has `completedTask` non-null. Skip the rest — without this guard,
            // non-owning platforms emit a degenerate DELIVERY_COMPLETED row via
            // deliveryCompletedPayload's "unknown" fallback.
            if (prevFlow.flow == Flow.PostTask && nextFlow.flow != Flow.PostTask) {
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
                    val completedTask = next.activeTask?.takeIf { it.taskId == p.activeTask?.taskId }
                        ?: next.recentTasks.lastOrNull { completedJobId == null || it.jobId == completedJobId }
                    // #564: a delivery completes a DROPOFF, never a PICKUP. A mid-stack add-on offer
                    // can grace-retire an in-flight PICKUP task and a transient/misrecognized
                    // delivery-summary frame then drives this PostTask-exit — fabricating a $0,
                    // customer-less "completion" of a store that was never delivered (06-21 seq98:
                    // Smoky Mo's pickup …32 completed at the moment the Burger King add-on was
                    // accepted). Only a task that actually reached the dropoff phase may complete.
                    if (completedTask != null && completedTask.phase == TaskPhase.DROPOFF) {
                        val retireSince = p.pendingDestructive
                            ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }?.since
                        val payload = deliveryCompletedPayload(
                            task = completedTask,
                            jobId = p.activeJob?.jobId,
                            completedAt = completedTask.completedAt ?: retireSince ?: obs.timestamp,
                            postTaskFields = p.lastPostTaskFields,
                            sessionEarnings = next.session?.runningEarnings ?: p.session?.runningEarnings,
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
                    }
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
                                ),
                            ),
                        )
                        add(AppEffect.StopOdometer)
                        add(AppEffect.UpdateBubble("Session Ended. Total: $earnings", ChatPersona.Dispatcher))
                        add(
                            AppEffect.CaptureScreenshot(
                                "DashSummary - ${endParsed.totalEarnings}",
                                category = EvidenceCategory.SESSION_SUMMARY,
                            ),
                        )
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
                                ),
                            ),
                        )
                        add(AppEffect.StopOdometer)
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
                        add(AppEffect.StartOdometer)
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
                            add(AppEffect.CancelTimeout(TimeoutType.SESSION_PAUSED_SAFETY))
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
                            add(AppEffect.CancelTimeout(TimeoutType.SESSION_PAUSED_SAFETY))
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
                        val durationMs = (pausedFields?.remainingMillis ?: 0L) + PAUSE_TIMEOUT_BUFFER_MS

                        val pausePayload = SessionPausedPayload(
                            sessionId = sessionId,
                            pausedAt = obs.timestamp,
                            remainingText = pausedFields?.remainingText,
                            remainingMillis = pausedFields?.remainingMillis,
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
                listOf(AppEffect.CancelTimeout(TimeoutType.GRACE_COMMIT))
            else -> emptyList()
        }
    }

    private fun diffTask(
        prev: PlatformRegion,
        next: PlatformRegion,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation,
    ): List<AppEffect> {
        // Diffs are computed from prev/next region state for EVERY observation type:
        // lazy expiry can retire a task on a non-flow observation (a routed timeout,
        // #342), and that closure must still emit DELIVERY_CONFIRMED (#345).
        val nextFlowVal = nextFlow.flow
        val sessionId = next.session?.sessionId ?: prev.session?.sessionId

        return buildList {
            val prevTask = prev.activeTask
            val nextTask = next.activeTask

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
                    add(AppEffect.ResumeOdometer)

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

            // Task phase changed — pickup → dropoff (pickup confirmed)
            if (prevTask?.phase == TaskPhase.PICKUP &&
                nextTask?.phase == TaskPhase.DROPOFF
            ) {
                val pickupConfirmed = pickupPayload(
                    task = prevTask,
                    storeName = prevTask.storeName ?: UNKNOWN_STORE,
                    confirmedAt = obs.timestamp,
                )
                val deliveryStart = deliveryPhasePayload(
                    task = nextTask,
                    phaseStartedAt = obs.timestamp,
                )
                add(logEffect(sessionId, AppEventType.PICKUP_CONFIRMED, obs.timestamp, pickupConfirmed))
                add(logEffect(sessionId, AppEventType.DELIVERY_NAV_STARTED, obs.timestamp, deliveryStart))
                add(AppEffect.ResumeOdometer)

                // #556: a completed SHOP pickup feeds the learned items/min. In-store time is
                // measured arrived→confirmed (the 0.8/min seed basis); the handler floors out noise.
                val shopItems = prevTask.itemsShopped ?: 0
                val shopArrivedAt = prevTask.arrivedAt
                if (prevTask.activity == PickupActivity.SHOPPING && shopItems > 0 && shopArrivedAt != null) {
                    add(
                        AppEffect.RecordShopRate(
                            itemsShopped = shopItems,
                            shopDurationMs = obs.timestamp - shopArrivedAt,
                            storeName = prevTask.storeName,
                            jobId = prevTask.jobId,
                            taskId = prevTask.taskId,
                        ),
                    )
                }

                // #568: store-flavored label ("Heading to H-E-B's customer") — friendlier than the
                // raw 6-char hash and it disambiguates a multi-store stack's drops.
                val customer = customerLabel(nextTask.storeName)
                add(AppEffect.UpdateBubble("Heading to $customer", ChatPersona.Customer(customer), dedupeScope = nextTask.taskId))
            }

            // Arrival detection — task subflow changed to ARRIVED
            if (nextTask != null && nextTask.arrivedAt != null &&
                (prevTask?.arrivedAt == null)
            ) {
                val arrivedOverride = triggerOverrideEffects(obs, TransitionTrigger.TASK_ARRIVED)
                if (arrivedOverride != null) {
                    addAll(arrivedOverride)
                } else {
                    add(AppEffect.PauseOdometer)

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

            // Post-task: delivery completed
            if (nextFlowVal == Flow.PostTask && prevFlow.flow != Flow.PostTask) {
                add(AppEffect.ResumeOdometer)
            }
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
        nextFlow: FlowRegion,
        obs: Observation,
    ): List<AppEffect> {
        if (nextFlow.flow != Flow.PostTask) return emptyList()
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
                    append("\nTip: ${item.type} • ${Formats.money(item.amount)}")
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
     */
    private fun diffRuleEffects(obs: Observation): List<AppEffect> {
        val flowObs = obs as? Observation.FlowObservation ?: return emptyList()
        if (flowObs.effects.isEmpty()) return emptyList()
        return flowObs.effects
            .filter { evaluateGate(it.onlyIf, flowObs.parsed) }
            .map { AppEffect.RequestEffect(it) }
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
                durationMs = EXPAND_SETTLE_MS,
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
        if (next.regions.flow.pendingOffer == null) return emptyList()
        return listOf(
            AppEffect.ScheduleTimeout(
                durationMs = EXPAND_SETTLE_MS,
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

    private fun resolveOfferOutcome(obs: Observation, prevOffer: PendingOffer? = null): AppEventType {
        // 0. Decline-commit latch (#594): a DECLINE-intent click already committed this offer's
        //    decline server-side. That decision is final — a later "Review offer"→Accept click
        //    cannot un-decline it — so the latch wins over lastClickIntent AND the direct-click
        //    fallback below.
        if (prevOffer?.declineCommittedAt != null) return AppEventType.OFFER_DECLINED
        // 1. Stored click intent on PendingOffer — covers the common case where
        //    the click was observed first and the resolving obs is a Screen
        when (prevOffer?.lastClickIntent) {
            OfferIntent.ACCEPT -> return AppEventType.OFFER_ACCEPTED
            OfferIntent.DECLINE -> return AppEventType.OFFER_DECLINED
        }
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
    private fun logEffect(
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

    private fun offerPayload(
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
        sessionEarningsAtCompletion = sessionEarnings,
    )

}
