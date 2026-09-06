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
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import timber.log.Timber

/**
 * #240 — the mode/session lifecycle effect diffs, extracted from [EffectMap] (past the #237
 * ceiling once the #438 B-series grew it to ~1450 lines). `internal` extensions on [EffectMap]
 * (mirroring the [OfferEffects]/[JobAcceptFlow] precedent) so they keep direct access to
 * [EffectMap.logEffect], [EffectMap.triggerOverrideEffects], and [EffectMap.graceConfig] — all
 * widened `private` → `internal` for this split, same as the earlier extractions. Pure move: no
 * behavior change. [diffGraceTimer], [diffModeResumeTimer], [diffSessionPaySettleTimer] and
 * [diffPauseSafetyTimer] — all four now one-liners over the shared [diffDeadlineTimer] body (#1029
 * D4, #1054 round 4) — house here (not a
 * separate file) because all three are grace/settle TIMER arming for the mode/session
 * lifecycle above them — [diffGraceTimer]
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
                    // #1030: a MISSED total is null now, so the bubble states the end without
                    // quoting a figure rather than rendering a fabricated "$0.00".
                    val earnings = endParsed.totalEarnings?.let { Formats.money(it) }
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
                    add(
                        AppEffect.UpdateBubble(
                            earnings?.let { "Session Ended. Total: $it" } ?: "Session Ended.",
                            ChatPersona.Dispatcher,
                            sessionId = sessionId,
                        )
                    )
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
                                // #1030: nothing is parsed on this path — `Session.runningEarnings`
                                // is a non-nullable `Double = 0.0`, so stamping it raw wrote a hard
                                // `0.0` that reads downstream as "the platform reported $0". Absent
                                // is the honest stamp. Rule owner: `RecordFolds.reportedEarningsOf`.
                                totalEarnings = prevSession.runningEarnings.takeIf { it > 0.0 },
                                platform = prev.platform.name, // #314 capture-gap: harden the log
                            ),
                        ),
                    )
                    // #438 B5: StopOdometer arbitrated at the cross-platform tier (see above).
                }
                // #867: the ENDED session (prevSession), not the diff-wide `sessionId` — on a
                // dash-replaced-by-a-fresh-dash edge that local resolves to the NEW session.
                add(AppEffect.EndSession(prev.platform.name, sessionId = prevSession.sessionId))
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
                        // #715: this used to branch on `lastTransitionKind == Unexpected` to
                        // distinguish a crash-recovery start from a normal one, but that kind
                        // was unreachable (no ruleset ever declared `outcomes`) — the branch
                        // always evaluated to INTERACTION. Struck along with the rest of the
                        // dormant Expected/Unexpected classification machinery.
                        source = SessionStartSource.INTERACTION,
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
                    add(AppEffect.UpdateBubble("Session resumed (grace)", sessionId = nextSession.sessionId))
                }

                // #1054 round 4: the SESSION_PAUSED_SAFETY cancel is no longer hand-built here —
                // `PlatformRegion.pauseSafetyDeadline` clears on the way out of Paused and
                // [diffPauseSafetyTimer] emits the cancel off that, like every other region timer.
                // Only the rule-declared override remains a per-site decision.
                if (prev.mode == Mode.Paused) {
                    triggerOverrideEffects(obs, TransitionTrigger.RESUME_FROM_PAUSE)?.let(::addAll)
                }
            }

            // Going offline. Session finalize (and any MODE_TO_OFFLINE
            // override) is handled by the `prevEnded` block above, which
            // defers while a grace window keeps the session alive. Only the
            // pause-safety-timer cancel remains here.
            prev.mode != Mode.Offline && next.mode == Mode.Offline -> {
                // #1054 round 4: as above — the cancel rides `pauseSafetyDeadline` clearing.
                if (prev.mode == Mode.Paused) {
                    triggerOverrideEffects(obs, TransitionTrigger.RESUME_FROM_PAUSE)?.let(::addAll)
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

                    val pausePayload = SessionPausedPayload(
                        sessionId = sessionId,
                        pausedAt = obs.timestamp,
                        remainingText = pausedFields?.remainingText,
                        remainingMillis = pausedFields?.remainingMillis,
                        platform = next.platform.name, // #314 capture-gap: harden the log
                    )
                    add(logEffect(sessionId, AppEventType.DASH_PAUSED, obs.timestamp, pausePayload))
                    // #1054 round 4: the timer itself is armed by [diffPauseSafetyTimer] off
                    // `PlatformRegion.pauseSafetyDeadline`, which the stepper set on this same
                    // transition. It used to be hand-built here, which is exactly why it could
                    // not survive a restore: the deadline existed only inside the engine's timer
                    // map, so a process death left a paused dash with nothing to end it.
                    add(AppEffect.UpdateBubble("Dash Paused!", sessionId = sessionId))
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
 *
 * #1054: this timer is not merely punctual either. The case it was built for — offline with the
 * app backgrounded — is exactly the case where no ordinary frame is coming to re-drive the lazy
 * expiry, so a fire the expiry failed to recognise stranded the session-end for hours. Its arm
 * carries a [ObservationPayload.GraceWake] identity so that can no longer happen.
 */
internal fun EffectMap.diffGraceTimer(
    prev: PlatformRegion,
    next: PlatformRegion,
    obs: Observation,
): List<AppEffect> = diffDeadlineTimer(
    prevDeadline = prev.pendingDestructive?.deadline,
    nextDeadline = next.pendingDestructive?.deadline,
    type = TimeoutType.GRACE_COMMIT,
    platform = next.platform,
    obs = obs,
)

/**
 * The ONE arm/cancel shape every region timer shares (#1029 D4, widened to the pause-safety net by
 * #1054 round 4). Each deadline-bearing pending on [PlatformRegion] expresses its wake identically —
 * schedule for the remaining time when a deadline appears or MOVES, cancel when the pending clears —
 * so the diffs below differ only in which field they read and which [TimeoutType] they key. Keeping
 * one body means a fix cannot land on three of four.
 *
 * A commit lands in the cancel branch too — harmless, the timer has already fired or no-ops.
 *
 * **Every arm carries its own identity** ([ObservationPayload.GraceWake] with the deadline it was
 * armed for) **and that deadline as an absolute instant** ([AppEffect.ScheduleTimeout.deadlineMs]).
 * The two do different jobs and both are load-bearing:
 *
 * - The **payload** is what the lazy expiry matches on. A fire carrying the pending's CURRENT
 *   deadline lapses it whenever it arrives, so the expiry never has to reason about the fire's own
 *   wall-clock stamp — which is armed as `deadline - obs.timestamp` and lands ON the deadline
 *   ordinarily, BEFORE it after an NTP step-back. And a fire from a REPLACED pending carries the
 *   OLD deadline, so it is inert by construction. That is why there is no early-wake re-arm branch
 *   here any more: nothing needs re-arming, because nothing gets lost.
 * - The **`deadlineMs`** is what the engine waits on. A stepper deadline IS a wall-clock instant
 *   (`obs.timestamp` is stamped from `System.currentTimeMillis()` at capture), so handing it over
 *   makes a tail-REPLAYED arm land on time instead of a full window late — the timer is armed
 *   against a replayed frame's timestamp, but waited out against the real clock. (`OFFER_EXPIRY`
 *   and `SETTLE_UI` do not do this yet; they stay on #1076.)
 */
internal fun EffectMap.diffDeadlineTimer(
    prevDeadline: Long?,
    nextDeadline: Long?,
    type: TimeoutType,
    platform: Platform,
    obs: Observation,
): List<AppEffect> {
    fun schedule(deadline: Long) = listOf(
        AppEffect.ScheduleTimeout(
            durationMs = (deadline - obs.timestamp).coerceAtLeast(1L),
            type = type,
            platform = platform,
            payload = ObservationPayload.GraceWake(deadline),
            deadlineMs = deadline,
        ),
    )
    return when {
        nextDeadline != null && (prevDeadline == null || prevDeadline != nextDeadline) ->
            schedule(nextDeadline)
        prevDeadline != null && nextDeadline == null ->
            listOf(AppEffect.CancelTimeout(type, platform))
        else -> emptyList()
    }
}

/**
 * Schedule/cancel the pause-safety net (#1054 round 4) — the
 * [PlatformRegion.pauseSafetyDeadline] mirror of the other three.
 *
 * This timer used to be hand-built at the Online→Paused site in [diffMode], with its two cancels
 * hand-built at the exits, and its deadline living ONLY inside `SideEffectEngine`'s in-memory timer
 * map. That is precisely why a paused dash could not survive a process death: nothing in state
 * described the countdown, so nothing could re-arm it, and a pocketed phone whose platform countdown
 * ended left the session live indefinitely — the next morning's dash then RESUMED it. Moving the
 * deadline into the region makes the net a first-class pending like the graces, re-armable by
 * `AppState.pendingDeadlineTimers()`, and collapses three hand-built sites into this one diff.
 */
internal fun EffectMap.diffPauseSafetyTimer(
    prev: PlatformRegion,
    next: PlatformRegion,
    obs: Observation,
): List<AppEffect> = diffDeadlineTimer(
    prevDeadline = prev.pauseSafetyDeadline,
    nextDeadline = next.pauseSafetyDeadline,
    type = TimeoutType.SESSION_PAUSED_SAFETY,
    platform = next.platform,
    obs = obs,
)

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
): List<AppEffect> = diffDeadlineTimer(
    prevDeadline = prev.pendingModeResume?.deadline,
    nextDeadline = next.pendingModeResume?.deadline,
    type = TimeoutType.MODE_RESUME_COMMIT,
    platform = next.platform,
    obs = obs,
)

/**
 * Schedule/cancel the wake-up timer for a parked dash running-total read (#1029) — the
 * [PlatformRegion.pendingSessionPay] mirror of [diffModeResumeTimer]. A SEPARATE
 * [TimeoutType.SESSION_PAY_SETTLE] for the same reason that one is separate: the park shares the
 * platform region with the destructive and resume graces, so a reused type's (type, platform)
 * timer key (#438 item 1) would cross-cancel a live one.
 *
 * This timer is LOAD-BEARING, not merely punctual — it is the only observation a settled park will
 * ever get (see [PlatformRegion.pendingSessionPay]), which is why the early-wake re-arm was built
 * here first (#1029 S5). #1054 round 4 deleted that branch outright: an arm now carries the deadline
 * it was armed for, so its fire is recognised whenever it lands and a superseded one is inert.
 *
 * Arm (or a re-arm with a new deadline — a different read replaced the park) schedules; the park
 * clearing (wheel at rest, a superseding direct write, leaving the read's surface, session
 * start/end, or the commit itself) cancels.
 */
internal fun EffectMap.diffSessionPaySettleTimer(
    prev: PlatformRegion,
    next: PlatformRegion,
    obs: Observation,
): List<AppEffect> = diffDeadlineTimer(
    prevDeadline = prev.pendingSessionPay?.deadline,
    nextDeadline = next.pendingSessionPay?.deadline,
    type = TimeoutType.SESSION_PAY_SETTLE,
    platform = next.platform,
    obs = obs,
)
