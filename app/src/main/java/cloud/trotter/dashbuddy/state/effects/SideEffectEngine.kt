package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredDao
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredEntity
import cloud.trotter.dashbuddy.domain.action.ActionTrigger
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
import cloud.trotter.dashbuddy.domain.config.EvidenceCategory
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.domain.model.state.TimeoutEvent
import cloud.trotter.dashbuddy.domain.pipeline.EffectVerb
import cloud.trotter.dashbuddy.domain.pipeline.PermissionTier
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.core.state.EffectExecutor
import cloud.trotter.dashbuddy.core.state.MetadataProvider
import cloud.trotter.dashbuddy.domain.di.DefaultDispatcher
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload

@Singleton
class SideEffectEngine @Inject constructor(
    private val appEventRepo: AppEventRepo,
    private val odometerEffectHandler: OdometerEffectHandler,
    private val tipEffectHandler: TipEffectHandler,
    private val bubbleManager: BubbleManager,
    private val offerEvaluator: cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator,
    private val strategyRepository: StrategyRepository,
    private val screenShotHandler: ScreenShotHandler,
    private val uiInteractionHandler: UiInteractionHandler,
    private val effectsFiredDao: EffectsFiredDao,
    private val ttsEffectHandler: TtsEffectHandler,
    private val permissionTierChecker: PermissionTierChecker,
    private val capabilityGrants: RuleCapabilityGrants,
    private val metadataProvider: MetadataProvider,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : EffectExecutor {

    // 1. OUTPUT STREAM: Events going BACK to the StateMachine (The Loopback)
    private val _events = MutableSharedFlow<StateEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<StateEvent> = _events.asSharedFlow()

    /**
     * Composite timer key (#438 item 1): the registry is keyed by (type, platform), not
     * type alone. Two paused platforms each hold their own SESSION_PAUSED_SAFETY timer,
     * and platform A's resume-cancel no longer untracks/kills platform B's timer of the
     * same type. Null platform = a non-platform-scoped timer of this type.
     */
    private data class TimerKey(val type: TimeoutType, val platform: Platform?)

    // Internal tracker for Timers (Replaces TimeoutHandler), keyed by (type, platform).
    private val activeTimers = ConcurrentHashMap<TimerKey, Job>()

    // Action throttle tracker: effectKey → last fired timestamp
    private val actionLastFiredAt = ConcurrentHashMap<String, Long>()

    // Offer notifications waiting out their post delay, keyed by offerHash so
    // an offer-resolved CancelOfferNotification can abort the post (#436).
    private val pendingOfferNotifications = ConcurrentHashMap<String, Job>()

    companion object {
        /** Default throttle between repeated firings of the same action. */
        const val DEFAULT_ACTION_THROTTLE_MS = 500L

        /**
         * Throttle between repeated firings of the same `RuleAction` per
         * platform (#425) — an app-owned bound on automated taps that no
         * ruleset content can loosen. Carried over from the former expand
         * click's rule-declared `throttleMs`.
         */
        const val RULE_ACTION_THROTTLE_MS = 1000L

        /**
         * Delay before posting the offer notification, so it lands AFTER the offer screenshot's
         * settle + capture and never covers the captured frame.
         */
        const val OFFER_NOTIFICATION_DELAY_MS = ScreenShotHandler.SETTLE_MS + 250L

        /** Keep effects_fired idempotency rows for 48h (#364). */
        private const val EFFECTS_RETENTION_MS = 48 * 60 * 60 * 1000L

        /** Prune the throttle map past this size (#436) — a slow per-offer leak otherwise. */
        private const val THROTTLE_MAP_PRUNE_THRESHOLD = 256

        /** Entries older than this can never gate again (≥ any declared throttle window). */
        private const val THROTTLE_ENTRY_TTL_MS = 10 * 60 * 1000L
    }

    /**
     * Entry point: The StateManager pushes an effect here.
     * We execute it in the provided scope.
     *
     * @param recovering When true (crash-recovery replay):
     *   - External effects (UI, sound, clicks) are suppressed.
     *   - Keyed effects are checked against `effects_fired` for idempotency.
     *   - Loopback effects (timers, evaluations) replay deterministically.
     */
    /**
     * Backstop for the effect coroutines this engine spawns (timers, delayed posts): an
     * uncaught failure must never reach the default handler and kill the process (#341).
     */
    private val effectExceptionHandler = CoroutineExceptionHandler { _, t ->
        Timber.tag("Effects").e(t, "SideEffectEngine: effect coroutine crashed (isolated)")
    }

    /**
     * Engine-owned execution context (#351). One serialized worker drains [queue], so
     * effects run strictly in [process] order — within a transition's effect list and
     * across transitions — and "execute, then markFired" holds (see [execute]'s tail).
     * Long-running waits (timers, delayed notifications) detach onto this scope and
     * never block the queue.
     */
    private val engineScope =
        CoroutineScope(defaultDispatcher + SupervisorJob() + effectExceptionHandler)

    private data class QueuedEffect(
        val effect: AppEffect,
        val recovering: Boolean,
        val correlationVersion: Long,
    )

    private val queue = Channel<QueuedEffect>(Channel.UNLIMITED)

    init {
        // Startup prune (#364): effects_fired gained a row per logged event and
        // grew unbounded — pruneOlderThan had zero callers. 48h retention
        // comfortably exceeds the 24h snapshot window recovery replays over.
        engineScope.launch {
            effectsFiredDao.pruneOlderThan(System.currentTimeMillis() - EFFECTS_RETENTION_MS)
        }
        engineScope.launch {
            for (item in queue) {
                try {
                    execute(item.effect, item.recovering, item.correlationVersion)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag("Effects").e(e, "Effect failed (isolated): %s", item.effect::class.simpleName)
                }
            }
        }
    }

    override fun process(effect: AppEffect, recovering: Boolean, correlationVersion: Long) {
        queue.trySend(QueuedEffect(effect, recovering, correlationVersion))
    }

    private suspend fun execute(effect: AppEffect, recovering: Boolean, correlationVersion: Long) {
        // Idempotency: skip keyed effects already fired. Consulted on the LIVE
        // path too (#436) — a non-crash restart used to re-fire keyed external
        // effects on re-observation because the check was recovery-only. The
        // table is pruned at 48h, so a key is "already fired" only within that
        // window (matching the snapshot horizon recovery replays over).
        val key = effect.effectKey
        if (key != null && effectsFiredDao.hasBeenFired(key)) {
            Timber.tag("Effects").d("Skipping already-fired effect: %s", key)
            return
        }

        // Suppress external effects during recovery
        if (recovering && isExternalEffect(effect)) {
            Timber.tag("Effects").d("Suppressing external effect during recovery: %s", effect::class.simpleName)
            return
        }
        when (effect) {
            // --- FIRE & FORGET (UI / IO) ---
            is AppEffect.LogEvent -> {
                // Inline in the worker (#351) so rows land in EffectMap emission
                // order. Entity assembly (payload encoding) + the device-state
                // metadata snapshot happen here at the edge (#354/#119), and the
                // event insert + its idempotency mark commit in ONE transaction —
                // the last crash seam between "did" and "recorded as did" closes.
                appEventRepo.insertAndMark(
                    event = effect.event,
                    metadataJson = metadataProvider.createMetadata(),
                    effectKey = effect.effectKey,
                    correlationVersion = correlationVersion,
                )
                return // marked inside the transaction — skip the markFired tail
            }

            is AppEffect.UpdateBubble -> {
                bubbleManager.postMessage(effect.text, effect.persona, effect.expand)
            }

            is AppEffect.CaptureScreenshot -> captureEvidence(effect)

            is AppEffect.PerformRuleAction -> {
                // The ONLY path that taps a third-party app (#425). The target
                // is ruleset data; everything that decides whether/where the
                // tap lands is app-owned: this throttle, the live tier check,
                // the consent gate (#417 — an AUTOMATION fire must be covered
                // by a granted, content-pinned capability looked up by
                // (sourceRuleId, action); a USER fire is its own consent),
                // and the package + label verification in the handler.
                val throttleKey = "action:${effect.action.wire}:${effect.platform.wire}"
                val now = System.currentTimeMillis()
                if ((actionLastFiredAt[throttleKey] ?: 0L) + RULE_ACTION_THROTTLE_MS > now) {
                    // .i not .v (#457): a throttled USER offer tap would otherwise be
                    // invisible under release log filtering — one of the silent drop
                    // points a shade Accept/Decline could die at.
                    Timber.tag("Effects").i(
                        "Throttled action %s (%s) — within %dms of the last fire",
                        throttleKey, effect.trigger, RULE_ACTION_THROTTLE_MS,
                    )
                    return
                }
                if (!permissionTierChecker.isGranted(PermissionTier.ACCESSIBILITY)) {
                    Timber.tag("Effects").w("Denied %s — ACCESSIBILITY tier not granted (fail closed)", effect.action.wire)
                    return
                }
                if (effect.trigger == ActionTrigger.AUTOMATION &&
                    !capabilityGrants.isActionGranted(effect.sourceRuleId, effect.action)
                ) {
                    Timber.tag("Effects").w(
                        "Denied %s — no granted capability for rule '%s' (fail closed)",
                        effect.action.wire, effect.sourceRuleId,
                    )
                    return
                }
                // #577 quick-decline: the auto-confirm of DoorDash's 2nd ("are you sure?") decline
                // button fires ONLY when the dasher enabled quick-declines. The setting is the consent;
                // gating here (not in the pure reducer) keeps EffectMap config-free. Off → no-op, the
                // dasher confirms manually.
                if (effect.action == RuleAction.CONFIRM_DECLINE &&
                    !strategyRepository.automationConfig.first().quickDeclinesEnabled
                ) {
                    Timber.tag("Effects").i("Skipped %s — quick declines off (dasher confirms manually)", effect.action.wire)
                    return
                }
                stampThrottle(throttleKey, now)
                Timber.tag("Effects").i("Performing %s on %s", effect.action.wire, effect.platform.wire)
                // #602: performVerifiedClick is suspend because it bounded-retries a
                // transient "no live windows" read (a SystemUI shade/lock takeover
                // between a notification tap and the re-resolve). We're already inside
                // this engine's suspend serialized worker, so the inline delay (<=1.5s,
                // only on that one branch) just stalls the effect queue rather than
                // reordering effects vs. subsequent state — the simpler choice over
                // detaching, which the engine's "detach long waits" guidance means for
                // genuinely multi-second waits, not this. Retry is USER-taps only
                // (#618 F2): an AUTOMATION fire follows a live recognition ms earlier,
                // so an empty enumeration there is a real departure, not a race.
                val clicked = uiInteractionHandler.performVerifiedClick(
                    ref = effect.targetRef,
                    expectedPackage = effect.platform.packageName,
                    expectation = effect.action.verification,
                    description = "${effect.action.wire} on ${effect.platform.wire} [${effect.sourceRuleId}]",
                    allowRetry = effect.trigger == ActionTrigger.USER,
                )
                // #618 F3: the retry can stretch stamp→dispatch to ~1.5s, which would
                // let a queued duplicate fire ~100ms after a late-landing tap. Re-stamp
                // at completion so the 1000ms spacing anchors to the actual dispatch.
                stampThrottle(throttleKey, System.currentTimeMillis())
                if (!clicked) {
                    Timber.tag("Effects").w(
                        "%s did not fire — target failed resolution/verification (fail closed, user acts manually)",
                        effect.action.wire,
                    )
                }
            }

            is AppEffect.RequestEffect -> {
                val effectKey = effect.effectKey
                val now = System.currentTimeMillis()
                val throttle = effect.effect.throttleMs ?: DEFAULT_ACTION_THROTTLE_MS
                val lastFired = actionLastFiredAt[effectKey] ?: 0L
                if (lastFired + throttle > now) {
                    Timber.tag("Effects").v("Throttled effect: %s", effectKey)
                    return
                }
                stampThrottle(effectKey, now)
                // A DENIED effect (tier or evidence gate) must not be marked
                // fired (#436): once gates are real (#417/#426), marking a
                // denial would make the effect "already fired" after the user
                // grants it — skipped on recovery and, now, on the live path.
                if (!dispatchRuleEffect(effect.effect)) return
            }

            is AppEffect.SpeakOffer -> ttsEffectHandler.speakOffer(effect.evaluation)

            is AppEffect.StartSession -> {
                bubbleManager.startSession(effect.sessionId, effect.platformName)
                // #438 B5: anchor THIS session's odometer miles (per-session anchor). External
                // effect → suppressed on recovery, which is correct: the anchor persisted across
                // the crash, so recovery reconciliation re-establishes tracking without re-anchoring.
                odometerEffectHandler.onSessionStarted(effect.sessionId)
            }
            is AppEffect.EndSession -> bubbleManager.endSession(effect.platformName)
            is AppEffect.StartOdometer -> odometerEffectHandler.startUp()
            is AppEffect.StopOdometer -> odometerEffectHandler.shutDown()
            is AppEffect.PauseOdometer -> odometerEffectHandler.pause()
            is AppEffect.ResumeOdometer -> odometerEffectHandler.resume()

            is AppEffect.RecordShopRate -> {
                // #556/#588: learn the dasher's shopping pace for THIS platform.
                val minutes = effect.shopDurationMs / 60_000.0
                val learned = strategyRepository.recordShopRate(effect.platform, effect.itemsShopped, minutes)
                val perMin = if (minutes > 0) effect.itemsShopped / minutes else 0.0
                // #551 P7: rate math + the platform wire are shareable INFO milestones (the wire is a
                // registry token, not PII); the merchant name (raw third-party UI text) stays DEBUG-only.
                // #731: the learned running mean lives only in the strategy DataStore, invisible to a
                // post-dash data pull — surface it here so the relearn trajectory is desk-visible.
                // A never-learned mean renders "?" (an out-of-band sample folds nothing) — never a
                // fake 0.00; Locale.ROOT keeps the desk grep stable on comma-decimal locales.
                val learnedStr =
                    learned.itemsPerMin?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "?"
                Timber.tag("ShopRate").i(
                    "recorded %d items / %.1f min = %.2f/min [%s] → learned %s/min (n=%d)",
                    effect.itemsShopped, minutes, perMin, effect.platform.wire,
                    learnedStr, learned.sampleCount,
                )
                // The INFO line above owns the rate math (one format string, not a drifting twin);
                // DEBUG adds only the merchant name, which is PII-tier.
                Timber.tag("ShopRate").d("recorded shop store=%s", effect.storeName ?: "?")
            }

            is AppEffect.ProcessTipNotification -> tipEffectHandler.process(engineScope, effect)

            // --- LOOPBACKS (Produces Events) ---

            is AppEffect.EvaluateOffer -> {
                // Loopback only: evaluate, then emit the decision back to the state machine. The
                // notification is posted by the PostOfferNotification effect EffectMap emits once
                // the evaluation lands on the pending offer — keeps this handler thin.
                // Eagerly-materialized config (#436): a value read after first
                // load instead of a cold DataStore combine blocking the worker
                // per offer; filterNotNull means an offer arriving before the
                // first load WAITS for real economics rather than scoring
                // against a guessed default.
                val config = strategyRepository.evaluationConfig.filterNotNull().first()
                // #588: resolve the offer's own platform's learned shop rate + seed into the economy
                // before scoring — a DoorDash-learned pace never prices an Instacart/Uber shop.
                val result = offerEvaluator.evaluate(effect.parsedOffer, config.forPlatform(effect.platform))
                // Emit the loopback observation directly (#402): the old
                // OfferEvaluationEvent was a 1:1 shim the bridge re-typed.
                _events.emit(
                    Observation.Loopback(
                        timestamp = System.currentTimeMillis(),
                        effect = Observation.Loopback.EFFECT_OFFER_EVALUATED,
                        // #438 item 8a: stamp the offer's platform (carried on the effect) so the
                        // loopback lands on the owning region — an Unknown-platform loopback steps
                        // no region post-#682, silently killing the offer's notification/TTS.
                        targetPlatform = effect.platform,
                        payload = ObservationPayload.EvaluationResult(
                            action = result.action.name,
                            offerHash = effect.offerHash,
                            evaluation = result,
                        ),
                    )
                )
            }

            is AppEffect.PostOfferNotification -> {
                // The bubble can't auto-expand from the background (#110 field test), so surface the
                // evaluation as a heads-up notification with Accept/Decline actions. Delayed so it
                // lands AFTER the offer screenshot's settle + capture (clean frame). Formatting is
                // BubbleManager's job at the UI edge (#436) — no Android text types here.
                // Detached: the delay must not block the queue (#351). Tracked
                // by offerHash with a self-removing completion handler (the
                // #406 timer pattern) so an offer resolved inside the delay
                // window cancels the post instead of surfacing an actionable
                // Accept/Decline for an offer that's already gone (#436).
                val hashKey = effect.offerHash ?: "no-hash"
                pendingOfferNotifications[hashKey]?.cancel()
                val job = engineScope.launch(start = CoroutineStart.LAZY) {
                    delay(OFFER_NOTIFICATION_DELAY_MS)
                    bubbleManager.postOfferNotification(effect.offer, effect.evaluation, effect.platform)
                }
                job.invokeOnCompletion { pendingOfferNotifications.remove(hashKey, job) }
                pendingOfferNotifications[hashKey] = job
                job.start()
            }

            is AppEffect.CancelOfferNotification -> {
                // Abort a still-pending (delayed) post — untracking is via the job's self-removing
                // completion handler.
                pendingOfferNotifications[effect.offerHash ?: "no-hash"]?.cancel()
                // #457: the offer heads-up is now a SEPARATE notification (its own id), not the
                // self-replacing bubble — so if it already posted, dismiss it explicitly when the
                // offer resolves (accept/decline/timeout) so an Accept/Decline banner can't outlive
                // the offer. #438 B4: pass the resolved offer's hash through so we dismiss ONLY that
                // offer's banner (concurrent/replacement offers each keep their own).
                bubbleManager.cancelOfferNotification(effect.offerHash)
            }

            // --- TIMING LOGIC (Pure Coroutines) ---

            is AppEffect.ScheduleTimeout ->
                scheduleTimer(effect.type, effect.durationMs, effect.platform, effect.payload)

            is AppEffect.CancelTimeout -> {
                // Untracking happens via the job's self-removing completion handler.
                // Keyed by (type, platform) so a resume cancels only THIS platform's timer.
                activeTimers[TimerKey(effect.type, effect.platform)]?.cancel()
            }
        }

        // "Execute, then mark" (#351): the effect's durable work above completed in this
        // worker before the idempotency record is written, so recovery can neither skip
        // an unfinished effect nor double-run a finished one beyond this single seam.
        // (LogEvent never reaches here — its insert+mark commit atomically, #354.)
        if (key != null) {
            effectsFiredDao.markFired(
                EffectsFiredEntity(
                    effectKey = key,
                    firedAt = System.currentTimeMillis(),
                    correlationVersion = correlationVersion,
                )
            )
        }
    }

    // =========================================================================
    // Rule-driven effect dispatch (all 14 verbs)
    // =========================================================================

    /**
     * Dispatch a [RequestedEffect] to the appropriate handler based on its verb.
     * Permission tier is checked before execution — denied verbs are logged and skipped.
     *
     * @return false when a gate (tier or evidence) DENIED the effect — the
     *   caller must then skip the `markFired` tail (#436): a denial recorded
     *   as fired would be skipped forever once the user grants the gate.
     */
    private suspend fun dispatchRuleEffect(e: RequestedEffect): Boolean {
        if (!permissionTierChecker.isGranted(e.verb.tier)) {
            Timber.tag("Effects").w("Denied effect %s — permission tier %s not granted", e.verb, e.verb.tier)
            return false
        }
        when (e.verb) {
            // --- Observation-driven verbs ---
            EffectVerb.SCREENSHOT -> return screenshotFromArgs(e.args)
            EffectVerb.BUBBLE -> bubbleFromArgs(e.args)
            EffectVerb.LOG -> logFromArgs(e.args)
            // Deliberate no-ops (#359): offer evaluation + speech fire from
            // EffectMap's eval-landing diff, never from rule verbs.
            EffectVerb.EVALUATE_OFFER, EffectVerb.SPEAK ->
                Timber.tag("Effects").d("Rule verb %s is EffectMap-driven — no-op at the engine", e.verb)

            // --- Lifecycle verbs ---
            EffectVerb.SESSION_START -> sessionStartFromArgs(e.args)
            EffectVerb.SESSION_END -> sessionEndFromArgs(e.args)
            // #438 B5 (vet L6): the odometer is arbitrated at the cross-platform tier
            // (EffectMap.diffCrossPlatform). RETIRED as rule verbs — a rule must not be able to
            // command the odometer directly (a future ruleset could otherwise zero a live session,
            // bypassing the arbiter). No shipped rule declares these; the branches are inert no-ops.
            EffectVerb.ODOMETER_START, EffectVerb.ODOMETER_STOP,
            EffectVerb.ODOMETER_PAUSE, EffectVerb.ODOMETER_RESUME ->
                Timber.tag("Effects").w("Rule verb %s is retired — the odometer is cross-platform arbitrated (#438 B5)", e.verb)
            EffectVerb.SCHEDULE_TIMEOUT -> scheduleTimeoutFromArgs(
                e.args,
                Platform.fromRuleId(e.ruleId).takeIf { it != Platform.Unknown },
            )
            EffectVerb.CANCEL_TIMEOUT -> cancelTimeoutFromArgs(
                e.args,
                Platform.fromRuleId(e.ruleId).takeIf { it != Platform.Unknown },
            )
        }
        return true
    }

    /** @return false when the evidence gate suppressed the capture (#436). */
    private fun screenshotFromArgs(args: Map<String, String>): Boolean {
        val prefix = args["prefix"] ?: "Rule"
        return captureEvidence(
            AppEffect.CaptureScreenshot(
                filenamePrefix = prefix,
                // Rule-declared (#426): the rule names its consent bucket via
                // the `category` arg; unknown or missing maps to null → denied.
                category = EvidenceCategory.fromWire(args["category"]),
            ),
        )
    }

    /**
     * THE evidence gate (#426): every screenshot — EffectMap-emitted or
     * rule-declared — passes through here, and fires only when the user's
     * `EvidenceConfig` allows its category (master toggle AND category
     * toggle; uncategorized → never). The config's startup default is
     * master-off, so the gate also fails closed before DataStore loads.
     *
     * @return false when suppressed — a suppression is a denial, never a fire (#436).
     */
    private fun captureEvidence(effect: AppEffect.CaptureScreenshot): Boolean {
        val config = strategyRepository.evidenceConfig.value
        if (!config.allows(effect.category)) {
            // #772: filenamePrefix can carry template-expanded rule data (uber.json5's
            // "Offer - {storeName}") — raw merchant text stays off the INFO+ stream.
            Timber.tag("Effects").i(
                "Evidence capture suppressed (category=%s) — EvidenceConfig denies it",
                effect.category,
            )
            Timber.tag("Effects").d("Suppressed capture prefix: %s", effect.filenamePrefix)
            return false
        }
        screenShotHandler.capture(engineScope, effect)
        return true
    }

    /**
     * Record a throttle stamp, pruning stale entries first (#436): the map
     * gains a key per offer/action and previously grew unboundedly over a
     * long session. Entries older than [THROTTLE_ENTRY_TTL_MS] (≥ any
     * declared throttle window) can never gate again — drop them once the
     * map is past [THROTTLE_MAP_PRUNE_THRESHOLD].
     */
    private fun stampThrottle(key: String, now: Long) {
        if (actionLastFiredAt.size > THROTTLE_MAP_PRUNE_THRESHOLD) {
            val cutoff = now - THROTTLE_ENTRY_TTL_MS
            actionLastFiredAt.entries.removeIf { it.value < cutoff }
        }
        actionLastFiredAt[key] = now
    }

    private fun bubbleFromArgs(args: Map<String, String>) {
        val text = args["text"] ?: return
        // Persona parsing is the SSOT ChatPersona.fromWire (#audit-2); the bubble
        // verb's schema can't carry a Merchant/Customer name, so none is supplied.
        val persona = ChatPersona.fromWire(args["persona"])
        bubbleManager.postMessage(text, persona)
    }

    private fun logFromArgs(args: Map<String, String>) {
        val type = args["type"] ?: "RULE_EFFECT"
        val payload = args["payload"]
        // #551 P7: a rule LOG payload is a rule-authored diagnostic that can template raw
        // third-party UI text ({rawText}, {storeName} — e.g. "TIP_RECEIVED: {rawText}"), so it is
        // a DEBUG firehose step, never the shareable INFO stream.
        Timber.tag("Effects").d("Rule LOG [%s]: %s", type, payload ?: "(no payload)")
    }

    private fun sessionStartFromArgs(args: Map<String, String>) {
        val platformName = args["platformName"] ?: "Unknown"
        val sessionId = "session-${System.currentTimeMillis()}"
        bubbleManager.startSession(sessionId, platformName)
    }

    private fun sessionEndFromArgs(args: Map<String, String>) {
        val platformName = args["platformName"]
        bubbleManager.endSession(platformName)
    }

    private fun scheduleTimeoutFromArgs(
        args: Map<String, String>,
        platform: Platform?,
    ) {
        val typeWire = args["type"] ?: return
        val type = try {
            TimeoutType.valueOf(typeWire)
        } catch (_: IllegalArgumentException) {
            Timber.tag("Effects").w("Unknown timeout type: %s", typeWire)
            return
        }
        val durationMs = args["durationMs"]?.toLongOrNull() ?: return
        scheduleTimer(type, durationMs, platform, payload = null)
    }

    /**
     * THE timer pattern (#406): LAZY start so the map entry exists before the
     * body can run; the completion handler removes only ITSELF (two-arg
     * remove), so an expiring timer can never untrack a replacement of the
     * same (type, platform) key (#341). Detached onto the engine scope — never
     * blocks the queue (#351). Previously hand-rolled twice in this file.
     *
     * Keyed by (type, platform) (#438 item 1): a schedule replaces only the
     * same platform's timer of that type, so two platforms' timers of the same
     * type coexist.
     */
    private fun scheduleTimer(
        type: TimeoutType,
        durationMs: Long,
        platform: Platform?,
        payload: ObservationPayload?,
    ) {
        val key = TimerKey(type, platform)
        activeTimers[key]?.cancel()
        val job = engineScope.launch(start = CoroutineStart.LAZY) {
            delay(durationMs)
            // #692 P7: level is per-type, not one blanket WARN under the catch-all `App` tag.
            // GRACE_COMMIT/MODE_RESUME_COMMIT/SESSION_PAUSED_SAFETY are all "a grace timer waking
            // a commit" verbatim — the taxonomy's defended-invariant WARN bucket (a graced
            // destructive commit, a graced mode resume, and the paused-too-long safety net all
            // fire because an expected screen-driven transition never showed up). SETTLE_UI and
            // OFFER_EXPIRY are routine steps of their OWN normal lifecycle, not invariants firing:
            // SETTLE_UI expired ~8x in one field dash as its ordinary debounce-settle path, and
            // OFFER_EXPIRY's common case is an offer overlay vanishing without a confirming frame
            // (expected — most offers resolve via an explicit screen/click well before this
            // fallback fires). The accept-latched no-op branch (a defended invariant in its own
            // right) lives downstream in OfferLifecycle/EffectMap and isn't distinguishable at
            // this call site, so it isn't logged here.
            when (type) {
                TimeoutType.GRACE_COMMIT,
                TimeoutType.MODE_RESUME_COMMIT,
                TimeoutType.SESSION_PAUSED_SAFETY,
                -> Timber.tag("Effects").w("Timer Expired: %s", type)

                TimeoutType.SETTLE_UI,
                TimeoutType.OFFER_EXPIRY,
                -> Timber.tag("Effects").d("Timer Expired: %s", type)
            }
            _events.emit(
                TimeoutEvent(
                    timestamp = System.currentTimeMillis(),
                    type = type,
                    platform = platform,
                    payload = payload,
                )
            )
        }
        job.invokeOnCompletion { activeTimers.remove(key, job) }
        activeTimers[key] = job
        job.start()
    }

    private fun cancelTimeoutFromArgs(args: Map<String, String>, platform: Platform?) {
        val typeWire = args["type"] ?: return
        val type = try {
            TimeoutType.valueOf(typeWire)
        } catch (_: IllegalArgumentException) {
            Timber.tag("Effects").w("Unknown timeout type: %s", typeWire)
            return
        }
        // Untracking happens via the job's self-removing completion handler.
        // Keyed by (type, platform) to mirror the rule-driven schedule (#438 item 1).
        activeTimers[TimerKey(type, platform)]?.cancel()
    }

    private fun isExternalEffect(effect: AppEffect): Boolean = when (effect) {
        is AppEffect.UpdateBubble,
        is AppEffect.CaptureScreenshot,
        is AppEffect.PerformRuleAction,
        is AppEffect.RequestEffect,
        is AppEffect.StartOdometer,
        is AppEffect.StopOdometer,
        is AppEffect.PauseOdometer,
        is AppEffect.ResumeOdometer,
        is AppEffect.RecordShopRate,
        is AppEffect.StartSession,
        is AppEffect.EndSession,
        is AppEffect.ProcessTipNotification,
        is AppEffect.SpeakOffer,
        is AppEffect.PostOfferNotification,
        -> true
        else -> false
    }
}