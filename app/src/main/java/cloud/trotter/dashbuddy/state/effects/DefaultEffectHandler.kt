package cloud.trotter.dashbuddy.state.effects

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.data.event.AppEventRepo
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.event.OfferEvaluationEvent
import cloud.trotter.dashbuddy.state.event.StateEvent
import cloud.trotter.dashbuddy.state.logic.OfferEvaluator
import cloud.trotter.dashbuddy.state.model.OfferAction
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
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
    private val screenShotHandler: ScreenShotHandler,
) : EffectHandler {

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
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

            is AppEffect.StartOdometer -> {
                odometerEffectHandler.startUp()
            }

            is AppEffect.StopOdometer -> {
                odometerEffectHandler.shutDown()
            }

            is AppEffect.EvaluateOffer -> {
                val result = offerEvaluator.evaluateOffer(effect.parsedOffer)
                dispatch(OfferEvaluationEvent(result.action))
                val persona = when (result.action) {
                    OfferAction.ACCEPT -> ChatPersona.GoodOffer
                    OfferAction.DECLINE -> ChatPersona.BadOffer
                    OfferAction.NOTHING -> ChatPersona.Inspector
                }
                bubbleManager.postMessage(result.message, persona)
            }

            is AppEffect.ClickNode -> {
                Timber.i("Executing Effect: Clicking Node (${effect.description})")
                // Robust utility call
                cloud.trotter.dashbuddy.util.AccNodeUtils.clickNode(effect.node.originalNode)
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