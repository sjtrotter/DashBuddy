package cloud.trotter.dashbuddy.state.effects

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.state.logic.OfferEvaluator
import cloud.trotter.dashbuddy.data.location.LocationService
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.StateManagerV2
import cloud.trotter.dashbuddy.state.event.OfferEvaluationEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cloud.trotter.dashbuddy.log.Logger as Log

/**
 * The Real-World implementation that interacts with Android Services, UI, and Hardware.
 */
class DefaultEffectHandler : EffectHandler {

    private val tag = "DefaultEffectHandler"

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun handle(effect: AppEffect, scope: CoroutineScope) {
        when (effect) {
            is AppEffect.SequentialEffect -> {
                effect.effects.forEach { childEffect ->
                    handle(childEffect, scope)
                }
            }

            is AppEffect.LogEvent -> {
                scope.launch(Dispatchers.IO) {
                    Log.v(tag, "Logging Event: ${effect.event.eventType}")
                    DashBuddyApplication.appEventRepo.insert(effect.event)
                }
            }

            is AppEffect.UpdateBubble -> {
                Log.i(tag, "Bubble Update: ${effect.text}")
                DashBuddyApplication.sendBubbleMessage(effect.text)
            }

            is AppEffect.CaptureScreenshot -> {
                ScreenShotHandler.capture(scope, effect)
            }

            is AppEffect.PlayNotificationSound -> {
                // Play sound logic
            }

            is AppEffect.ProcessTipNotification -> {
                TipEffectHandler.process(scope, effect)
            }

            is AppEffect.ScheduleTimeout -> {
                TimeoutHandler.schedule(scope, effect.durationMs, type = effect.type)
            }

            is AppEffect.CancelTimeout -> {
                TimeoutHandler.cancel(effect.type)
            }

            is AppEffect.StartOdometer -> {
                OdometerEffectHandler.startUp()
            }

            is AppEffect.StopOdometer -> {
                OdometerEffectHandler.shutDown()
            }

            is AppEffect.EvaluateOffer -> {
                val result = OfferEvaluator.evaluateOffer(effect.parsedOffer)
                StateManagerV2.dispatch(OfferEvaluationEvent(result.offerAction))
                DashBuddyApplication.sendBubbleMessage(result.bubbleMessage)
            }

            is AppEffect.ClickNode -> {
                Log.i(tag, "Executing Effect: Clicking Node (${effect.description})")
                // Robust utility call
                cloud.trotter.dashbuddy.util.AccNodeUtils.clickNode(effect.node.originalNode)
            }

            is AppEffect.Delayed -> {
                scope.launch {
                    delay(effect.delayMs)
                    handle(effect.effect, scope) // Recursive call after delay
                }
            }

            is AppEffect.SendKeepAlive -> {
                try {
                    val intent = Intent(
                        DashBuddyApplication.context,
                        LocationService::class.java
                    ).apply {
                        action = LocationService.ACTION_KEEP_ALIVE
                    }
                    DashBuddyApplication.context.startForegroundService(intent)
                } catch (e: Exception) {
                    Log.e(tag, "Failed to send KeepAlive", e)
                }
            }
        }
    }
}