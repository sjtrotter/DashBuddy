package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredDao
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredEntity
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.model.state.OfferEvaluationEvent
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.domain.model.state.TimeoutEvent
import cloud.trotter.dashbuddy.domain.pipeline.EffectVerb
import cloud.trotter.dashbuddy.domain.pipeline.PermissionTier
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.core.state.EffectExecutor
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import cloud.trotter.dashbuddy.ui.formatters.toNotificationSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

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
) : EffectExecutor {

    // 1. OUTPUT STREAM: Events going BACK to the StateMachine (The Loopback)
    private val _events = MutableSharedFlow<StateEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<StateEvent> = _events.asSharedFlow()

    // Internal tracker for Timers (Replaces TimeoutHandler)
    private val activeTimers = ConcurrentHashMap<Any, Job>()

    // Action throttle tracker: effectKey → last fired timestamp
    private val actionLastFiredAt = ConcurrentHashMap<String, Long>()

    companion object {
        /** Default throttle between repeated firings of the same action. */
        const val DEFAULT_ACTION_THROTTLE_MS = 500L

        /**
         * Delay before auto-expanding the offer bubble, so it lands AFTER the offer
         * screenshot's settle + capture and never covers the captured frame.
         */
        const val OFFER_BUBBLE_EXPAND_DELAY_MS = ScreenShotHandler.SETTLE_MS + 250L
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
    override fun process(effect: AppEffect, scope: CoroutineScope, recovering: Boolean) {
        scope.launch(Dispatchers.Default) {
            execute(effect, scope, recovering)
        }
    }

    private suspend fun execute(effect: AppEffect, scope: CoroutineScope, recovering: Boolean) {
        // Idempotency: skip keyed effects already fired
        val key = effect.effectKey
        if (key != null && recovering) {
            if (effectsFiredDao.hasBeenFired(key)) {
                Timber.d("Skipping already-fired effect: %s", key)
                return
            }
        }

        // Suppress external effects during recovery
        if (recovering && isExternalEffect(effect)) {
            Timber.d("Suppressing external effect during recovery: %s", effect::class.simpleName)
            return
        }
        when (effect) {
            // --- FIRE & FORGET (UI / IO) ---
            is AppEffect.LogEvent -> {
                scope.launch(Dispatchers.IO) {
                    appEventRepo.insert(effect.event)
                }
            }

            is AppEffect.UpdateBubble -> {
                bubbleManager.postMessage(effect.text, effect.persona, effect.expand)
            }

            is AppEffect.CaptureScreenshot -> {
                screenShotHandler.capture(scope, effect)
            }

            is AppEffect.ClickNode -> {
                Timber.i("Executing Effect: Clicking Node (${effect.description})")
                uiInteractionHandler.performClick(effect.node, effect.description)
            }

            is AppEffect.PerformOfferAction -> {
                val template = offerActionNode(effect.platform, effect.action)
                if (template != null) {
                    Timber.i("Performing offer action: ${effect.action} on ${effect.platform.wire}")
                    uiInteractionHandler.performClick(template, "Bubble ${effect.action}")
                } else {
                    Timber.w("No offer-action node for ${effect.platform.wire}/${effect.action}")
                }
            }

            is AppEffect.RequestEffect -> {
                val effectKey = effect.effectKey
                val now = System.currentTimeMillis()
                val throttle = effect.effect.throttleMs ?: DEFAULT_ACTION_THROTTLE_MS
                val lastFired = actionLastFiredAt[effectKey] ?: 0L
                if (lastFired + throttle > now) {
                    Timber.v("Throttled effect: %s", effectKey)
                    return
                }
                actionLastFiredAt[effectKey] = now
                dispatchRuleEffect(effect.effect, scope)
            }

            is AppEffect.PlayNotificationSound -> { /* Implementation */
            }

            is AppEffect.SpeakOffer -> ttsEffectHandler.speakOffer(effect.parsedOffer, effect.platformName)

            is AppEffect.StartSession -> bubbleManager.startSession(effect.sessionId, effect.platformName)
            is AppEffect.EndSession -> bubbleManager.endSession(effect.platformName)
            is AppEffect.StartOdometer -> odometerEffectHandler.startUp()
            is AppEffect.StopOdometer -> odometerEffectHandler.shutDown()
            is AppEffect.PauseOdometer -> odometerEffectHandler.pause()
            is AppEffect.ResumeOdometer -> odometerEffectHandler.resume()

            is AppEffect.ProcessTipNotification -> tipEffectHandler.process(scope, effect)

            // --- LOOPBACKS (Produces Events) ---

            is AppEffect.EvaluateOffer -> {
                val config = strategyRepository.evaluationConfigFlow.first()

                val result = offerEvaluator.evaluate(effect.parsedOffer, config)

                // 1. Emit the Decision back to State Machine
                _events.emit(OfferEvaluationEvent(result.action, result))

                // 2. Post the offer as a heads-up notification with Accept/Decline actions — the
                // bubble can't auto-expand from the background (#110 field test). Launched on a
                // short delay so the heads-up lands AFTER the offer screenshot's settle+capture.
                val persona = when (result.action) {
                    OfferAction.ACCEPT -> ChatPersona.GoodOffer
                    OfferAction.DECLINE -> ChatPersona.BadOffer
                    OfferAction.MANUAL_REVIEW -> ChatPersona.Inspector
                    OfferAction.NOTHING -> ChatPersona.Inspector
                }
                val summary = result.toNotificationSummary()
                scope.launch {
                    delay(OFFER_BUBBLE_EXPAND_DELAY_MS)
                    bubbleManager.postOfferNotification(summary, persona)
                }
            }

            // --- TIMING LOGIC (Pure Coroutines) ---

            is AppEffect.ScheduleTimeout -> {
                // Cancel existing timer of this type
                activeTimers[effect.type]?.cancel()

                // Start new timer
                val job = scope.launch {
                    delay(effect.durationMs)
                    Timber.w("Timer Expired: ${effect.type}")

                    // Emit Timeout Event back to State Machine
                    _events.emit(TimeoutEvent(type = effect.type, payload = effect.payload))

                    activeTimers.remove(effect.type)
                }
                activeTimers[effect.type] = job
            }

            is AppEffect.CancelTimeout -> {
                activeTimers[effect.type]?.cancel()
                activeTimers.remove(effect.type)
            }

            is AppEffect.SequentialEffect -> {
                effect.effects.forEach { child -> execute(child, scope, recovering) }
            }
        }

        // Record keyed effect as fired for idempotency
        if (key != null) {
            scope.launch(Dispatchers.IO) {
                effectsFiredDao.markFired(
                    EffectsFiredEntity(
                        effectKey = key,
                        firedAt = System.currentTimeMillis(),
                        correlationVersion = 0, // caller can set if needed
                    )
                )
            }
        }
    }

    // =========================================================================
    // Rule-driven effect dispatch (all 14 verbs)
    // =========================================================================

    /**
     * Dispatch a [RequestedEffect] to the appropriate handler based on its verb.
     * Permission tier is checked before execution — denied verbs are logged and skipped.
     */
    private suspend fun dispatchRuleEffect(e: RequestedEffect, scope: CoroutineScope) {
        if (!isPermissionGranted(e.verb.tier)) {
            Timber.w("Denied effect %s — permission tier %s not granted", e.verb, e.verb.tier)
            return
        }
        when (e.verb) {
            // --- Observation-driven verbs ---
            EffectVerb.CLICK -> resolveAndClick(e)
            EffectVerb.SCREENSHOT -> screenshotFromArgs(scope, e.args)
            EffectVerb.BUBBLE -> bubbleFromArgs(e.args)
            EffectVerb.LOG -> logFromArgs(e.args, scope)
            EffectVerb.EVALUATE_OFFER -> {
                Timber.d("Rule-driven evaluate_offer — requires offer context from EffectMap")
            }
            EffectVerb.SPEAK -> {
                Timber.d("Rule-driven speak — requires offer context from EffectMap")
            }

            // --- Lifecycle verbs ---
            EffectVerb.SESSION_START -> sessionStartFromArgs(e.args)
            EffectVerb.SESSION_END -> sessionEndFromArgs(e.args)
            EffectVerb.ODOMETER_START -> odometerEffectHandler.startUp()
            EffectVerb.ODOMETER_STOP -> odometerEffectHandler.shutDown()
            EffectVerb.ODOMETER_PAUSE -> odometerEffectHandler.pause()
            EffectVerb.ODOMETER_RESUME -> odometerEffectHandler.resume()
            EffectVerb.SCHEDULE_TIMEOUT -> scheduleTimeoutFromArgs(scope, e.args)
            EffectVerb.CANCEL_TIMEOUT -> cancelTimeoutFromArgs(e.args)
        }
    }

    /**
     * Resolve a [RequestedEffect]'s [NodeRef] against the live UI tree and click.
     */
    private fun resolveAndClick(effect: RequestedEffect) {
        val ref = effect.targetRef ?: run {
            Timber.w("CLICK effect missing targetRef: %s", effect.ruleId)
            return
        }
        val template = cloud.trotter.dashbuddy.domain.model.accessibility.UiNode(
            viewIdResourceName = ref.viewIdSuffix,
            text = ref.text,
            className = ref.classNameHint,
            boundsInScreen = ref.boundsInScreen,
        )
        Timber.i("Auto-Click [%s]: target id=%s", effect.ruleId, ref.viewIdSuffix)
        uiInteractionHandler.performClick(template, "Auto-Click [${effect.ruleId}]")
    }

    /**
     * Resolve the platform's offer Accept/Decline button to a click template. DoorDash-only
     * for now; Decline targets the *initial* decline button (its confirm dialog is left to the
     * user in Native mode — auto-confirm is #110 Stage 2c). See #85 for the GigPlatform interface.
     */
    private fun offerActionNode(platform: Platform, action: OfferAction): UiNode? {
        if (platform != Platform.DoorDash) return null
        val pkg = platform.packageName ?: return null
        return when (action) {
            OfferAction.ACCEPT -> UiNode(viewIdResourceName = "$pkg:id/accept_button", text = "Accept")
            OfferAction.DECLINE -> UiNode(viewIdResourceName = "$pkg:id/secondary_action_button_dash_plus")
            else -> null
        }
    }

    private fun screenshotFromArgs(scope: CoroutineScope, args: Map<String, String>) {
        val prefix = args["prefix"] ?: "Rule"
        screenShotHandler.capture(scope, AppEffect.CaptureScreenshot(filenamePrefix = prefix))
    }

    private fun bubbleFromArgs(args: Map<String, String>) {
        val text = args["text"] ?: return
        val persona = resolvePersona(args["persona"])
        bubbleManager.postMessage(text, persona)
    }

    private fun logFromArgs(args: Map<String, String>, scope: CoroutineScope) {
        val type = args["type"] ?: "RULE_EFFECT"
        val payload = args["payload"]
        Timber.i("Rule LOG [%s]: %s", type, payload ?: "(no payload)")
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

    private suspend fun scheduleTimeoutFromArgs(scope: CoroutineScope, args: Map<String, String>) {
        val typeWire = args["type"] ?: return
        val type = try {
            TimeoutType.valueOf(typeWire)
        } catch (_: IllegalArgumentException) {
            Timber.w("Unknown timeout type: %s", typeWire)
            return
        }
        val durationMs = args["durationMs"]?.toLongOrNull() ?: return

        activeTimers[type]?.cancel()
        val job = scope.launch {
            delay(durationMs)
            Timber.w("Timer Expired (rule): %s", type)
            _events.emit(TimeoutEvent(type = type))
            activeTimers.remove(type)
        }
        activeTimers[type] = job
    }

    private fun cancelTimeoutFromArgs(args: Map<String, String>) {
        val typeWire = args["type"] ?: return
        val type = try {
            TimeoutType.valueOf(typeWire)
        } catch (_: IllegalArgumentException) {
            Timber.w("Unknown timeout type: %s", typeWire)
            return
        }
        activeTimers[type]?.cancel()
        activeTimers.remove(type)
    }

    private fun resolvePersona(wire: String?): ChatPersona = when (wire?.lowercase()) {
        "dispatcher" -> ChatPersona.Dispatcher
        "system" -> ChatPersona.System
        "earnings" -> ChatPersona.Earnings
        "inspector" -> ChatPersona.Inspector
        "navigator" -> ChatPersona.Navigator
        "shopper" -> ChatPersona.Shopper
        "good_offer" -> ChatPersona.GoodOffer
        "bad_offer" -> ChatPersona.BadOffer
        else -> ChatPersona.Dispatcher
    }

    /**
     * Check if the given [PermissionTier] is granted.
     *
     * For alpha (single user, all permissions already granted via system settings),
     * this returns true for all tiers. When multi-user or permission-gated features
     * are needed, swap in a DataStore-backed implementation.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun isPermissionGranted(tier: PermissionTier): Boolean = true

    private fun isExternalEffect(effect: AppEffect): Boolean = when (effect) {
        is AppEffect.UpdateBubble,
        is AppEffect.PlayNotificationSound,
        is AppEffect.CaptureScreenshot,
        is AppEffect.ClickNode,
        is AppEffect.RequestEffect,
        is AppEffect.StartOdometer,
        is AppEffect.StopOdometer,
        is AppEffect.PauseOdometer,
        is AppEffect.ResumeOdometer,
        is AppEffect.StartSession,
        is AppEffect.EndSession,
        is AppEffect.ProcessTipNotification,
        is AppEffect.SpeakOffer,
        -> true
        else -> false
    }
}