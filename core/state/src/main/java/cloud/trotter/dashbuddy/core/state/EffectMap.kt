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
     * one atomic read at each use site, never collected inside a reducer (each
     * value is stored into state/a timer immediately, so a mid-[diff] config flip
     * is equivalent to the edit landing between steps); defaults to code
     * constants when unbound so `EffectMap()` in tests is behavior-identical.
     * Replay-determinism tradeoff pre-accepted (see [GraceConfigProvider]).
     */
    internal val graceConfig: GraceConfigProvider,
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

            // #438 B5/#240: the DELIVERY_COMPLETED mint (PostTask-exit + the #596 close-out
            // sweep) extracted to DeliveryCompletionEffects.kt as one unit — both blocks share the
            // emittedThisStep dual-mint-exclusivity set (amdt #2), so they moved together.
            addAll(diffDeliveryCompletion(p, next, actedPrevFlow, actedNextFlow, obs))
            // #810 B1: the job-close accept-reconciliation tripwire — diffs the activeJob close
            // (any in-scope close routes through completeActiveJob; endSession is excluded inside).
            // Emitted AFTER diffDeliveryCompletion (#810 B2 review F1) so the JOB_ACCEPT_MISMATCH
            // event sequences AFTER the closing job's final DELIVERY_COMPLETED (the #596 close-out
            // sweep mints that drop on this SAME close step). This makes the B2 Tier-1 store-evidence
            // reconcile paging-independent — the projector's evidence read sees every delivered row of
            // the job by construction. Behaviorally inert to B1 (diffJobClose only diffs prev/next +
            // emits a log effect; the sweep's emittedThisStep set is local to diffDeliveryCompletion).
            addAll(diffJobClose(p, next, obs))
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
    internal fun triggerOverrideEffects(
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

    // Pure domain emission (#354): payload encoding + device metadata happen at the
    // executor edge. occurredAt is normally the OBSERVATION timestamp, so the LogEvent's
    // default idempotency key ("log:<type>:<occurredAt>") is identical between live
    // execution and recovery replay (#300). Exception: PICKUP_CONFIRMED passes
    // Task.completedAt (which can be grace-armed, #732) instead of obs.timestamp — it
    // stays idempotent because it always supplies its own effectKeyOverride (taskId-scoped,
    // see AppEffect.kt ~L44) rather than relying on the default occurredAt-derived key.
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
}
