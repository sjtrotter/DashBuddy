package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredDao
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredEntity
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.state.OfferEvaluationEvent
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.domain.model.state.TimeoutEvent
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import cloud.trotter.dashbuddy.ui.formatters.toAnnotatedString
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
) {

    // 1. OUTPUT STREAM: Events going BACK to the StateMachine (The Loopback)
    private val _events = MutableSharedFlow<StateEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<StateEvent> = _events.asSharedFlow()

    // Internal tracker for Timers (Replaces TimeoutHandler)
    private val activeTimers = ConcurrentHashMap<Any, Job>()

    // Action throttle tracker: effectKey → last fired timestamp
    private val actionLastFiredAt = ConcurrentHashMap<String, Long>()

    companion object {
        /** Default throttle between repeated firings of the same action. */
        const val DEFAULT_ACTION_THROTTLE_MS = 500L
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
    fun process(effect: AppEffect, scope: CoroutineScope, recovering: Boolean = false) {
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
                bubbleManager.postMessage(effect.text, effect.persona)
            }

            is AppEffect.CaptureScreenshot -> {
                screenShotHandler.capture(scope, effect)
            }

            is AppEffect.ClickNode -> {
                Timber.i("Executing Effect: Clicking Node (${effect.description})")
                uiInteractionHandler.performClick(effect.node, effect.description)
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
                when (effect.effect.verb) {
                    cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.CLICK -> resolveAndClick(effect.effect)
                    else -> Timber.w("Unhandled effect verb: %s", effect.effect.verb)
                }
            }

            is AppEffect.PlayNotificationSound -> { /* Implementation */
            }

            is AppEffect.SpeakOffer -> ttsEffectHandler.speakOffer(effect.parsedOffer, effect.platformName)

            is AppEffect.StartDash -> bubbleManager.startDash(effect.dashId, effect.platformName)
            is AppEffect.EndDash -> bubbleManager.endDash(effect.platformName)
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
                _events.emit(OfferEvaluationEvent(result.action))

                // 2. Show the Bubble (Side Effect)
                val persona = when (result.action) {
                    OfferAction.ACCEPT -> ChatPersona.GoodOffer
                    OfferAction.DECLINE -> ChatPersona.BadOffer
                    OfferAction.MANUAL_REVIEW -> ChatPersona.Inspector
                    OfferAction.NOTHING -> ChatPersona.Inspector
                }
                bubbleManager.postMessage(result.toAnnotatedString(), persona)
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
                    _events.emit(TimeoutEvent(type = effect.type))

                    activeTimers.remove(effect.type)
                }
                activeTimers[effect.type] = job
            }

            is AppEffect.CancelTimeout -> {
                activeTimers[effect.type]?.cancel()
                activeTimers.remove(effect.type)
            }

            is AppEffect.Delayed -> {
                delay(effect.delayMs)
                execute(effect.effect, scope, recovering)
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

    /**
     * Resolve a [RequestedEffect]'s [NodeRef] against the live UI tree and click.
     *
     * Builds a template [UiNode] from the [NodeRef] fingerprint and delegates
     * to [UiInteractionHandler.performClick], which handles live-root lookup
     * and native-node matching internally.
     */
    private fun resolveAndClick(effect: cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect) {
        val ref = effect.targetRef ?: run {
            Timber.w("CLICK effect missing targetRef: %s", effect.ruleId)
            return
        }
        // Build a minimal UiNode template for performClick's matching logic
        val template = cloud.trotter.dashbuddy.domain.model.accessibility.UiNode(
            viewIdResourceName = ref.viewIdSuffix,
            text = ref.text,
            className = ref.classNameHint,
        )
        Timber.i("Auto-Click [%s]: target id=%s", effect.ruleId, ref.viewIdSuffix)
        uiInteractionHandler.performClick(template, "Auto-Click [${effect.ruleId}]")
    }

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
        is AppEffect.StartDash,
        is AppEffect.EndDash,
        is AppEffect.ProcessTipNotification,
        is AppEffect.SpeakOffer,
        -> true
        else -> false
    }
}