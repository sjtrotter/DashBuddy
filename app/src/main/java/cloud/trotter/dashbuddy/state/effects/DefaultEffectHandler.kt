package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.domain.config.EvaluationConfig
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.state.OfferEvaluationEvent
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.logic.OfferEvaluator
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import cloud.trotter.dashbuddy.ui.formatters.toSpannableString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Real-World implementation that interacts with Android Services, UI, and Hardware.
 */
@Singleton
class DefaultEffectHandler @Inject constructor(
    private val appEventRepo: AppEventRepo,
    private val odometerEffectHandler: OdometerEffectHandler,
    private val timeoutHandler: TimeoutHandler,
    private val tipEffectHandler: TipEffectHandler,
    private val bubbleManager: BubbleManager,
    private val offerEvaluator: OfferEvaluator,
    private val offerEvaluatorV2: cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator,
    private val strategyRepository: StrategyRepository,
    private val screenShotHandler: ScreenShotHandler,
    private val uiInteractionHandler: UiInteractionHandler,
) : EffectHandler {

    override fun handle(
        effect: AppEffect,
        scope: CoroutineScope,
        dispatch: (StateEvent) -> Unit
    ) {
        when (effect) {
            is AppEffect.SequentialEffect -> {
                effect.effects.forEach { childEffect ->
                    handle(childEffect, scope, dispatch)
                }
            }

            is AppEffect.LogEvent -> {
                scope.launch(Dispatchers.IO) {
                    Timber.v("Logging Event: ${effect.event.eventType}")
                    appEventRepo.insert(effect.event)
                }
            }

            is AppEffect.UpdateBubble -> {
                Timber.i("Bubble Update: ${effect.text}")
                bubbleManager.postMessage(effect.text, effect.persona)
            }

            is AppEffect.CaptureScreenshot -> {
                screenShotHandler.capture(scope, effect)
            }

            is AppEffect.PlayNotificationSound -> {
                // Play sound logic
            }

            is AppEffect.ProcessTipNotification -> {
                tipEffectHandler.process(scope, effect)
            }

            is AppEffect.ScheduleTimeout -> {
                timeoutHandler.schedule(scope, effect.durationMs, type = effect.type)
            }

            is AppEffect.CancelTimeout -> {
                timeoutHandler.cancel(effect.type)
            }

            is AppEffect.StartDash -> {
                bubbleManager.startDash(effect.dashId)
            }

            is AppEffect.EndDash -> {
                if (bubbleManager.activeDashId.value != null) bubbleManager.endDash()
            }

            is AppEffect.StartOdometer -> {
                odometerEffectHandler.startUp()
            }

            is AppEffect.StopOdometer -> {
                odometerEffectHandler.shutDown()
            }

            is AppEffect.EvaluateOffer -> {
                var config: EvaluationConfig
                scope.launch(Dispatchers.IO) {
                    config = strategyRepository.getEvaluationConfig()
                    val result = offerEvaluatorV2.evaluate(effect.parsedOffer, config)

                    dispatch(OfferEvaluationEvent(result.action))
                    val persona = when (result.action) {
                        OfferAction.ACCEPT -> ChatPersona.GoodOffer
                        OfferAction.DECLINE -> ChatPersona.BadOffer
                        OfferAction.NOTHING -> ChatPersona.Inspector
                    }
                    bubbleManager.postMessage(result.toSpannableString(), persona)
                }
            }

            is AppEffect.ClickNode -> {
                Timber.i("Executing Effect: Clicking Node (${effect.description})")
                uiInteractionHandler.performClick(effect.node, effect.description)
            }

            is AppEffect.Delayed -> {
                scope.launch {
                    delay(effect.delayMs)
                    handle(effect.effect, scope, dispatch) // Recursive call after delay
                }
            }
        }
    }
}