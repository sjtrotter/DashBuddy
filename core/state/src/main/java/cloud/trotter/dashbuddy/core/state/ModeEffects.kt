package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionEndSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionPausedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.TransitionKind
import timber.log.Timber

/**
 * #240 — the mode/session lifecycle effect diffs, extracted from [EffectMap] (past the #237
 * ceiling once the #438 B-series grew it to ~1450 lines). `internal` extensions on [EffectMap]
 * (mirroring the [OfferEffects]/[JobAcceptFlow] precedent) so they keep direct access to
 * [EffectMap.logEffect], [EffectMap.triggerOverrideEffects], and [EffectMap.graceConfig] — all
 * widened `private` → `internal` for this split, same as the earlier extractions. Pure move: no
 * behavior change. [diffGraceTimer] and [diffModeResumeTimer] house here (not a separate file)
 * because both are grace/resume TIMER arming for the mode lifecycle above them — [diffGraceTimer]
 * generically watches [cloud.trotter.dashbuddy.domain.state.PlatformRegion.pendingDestructive]
 * (session-end AND task-retire share the one mechanism), but it is called immediately after
 * [diffMode] in [EffectMap.diffPlatformRegion] and is small enough that a fourth file would be
 * more indirection than the ~90 combined lines warrant.
 */
internal fun EffectMap.diffMode(
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
internal fun EffectMap.diffGraceTimer(
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
internal fun EffectMap.diffModeResumeTimer(
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
