package cloud.trotter.dashbuddy.state.effects

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.data.event.AppEventRepo
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.event.OfferEvaluationEvent
import cloud.trotter.dashbuddy.state.event.StateEvent
import cloud.trotter.dashbuddy.state.event.TimeoutEvent
import cloud.trotter.dashbuddy.state.logic.OfferEvaluator
import cloud.trotter.dashbuddy.state.model.OfferAction
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import cloud.trotter.dashbuddy.util.AccNodeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val offerEvaluator: OfferEvaluator,
    private val screenShotHandler: ScreenShotHandler,
) {

    // 1. OUTPUT STREAM: Events going BACK to the StateMachine (The Loopback)
    private val _events = MutableSharedFlow<StateEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<StateEvent> = _events.asSharedFlow()

    // Internal tracker for Timers (Replaces TimeoutHandler)
    private val activeTimers = ConcurrentHashMap<Any, Job>()

    /**
     * Entry point: The StateManager pushes an effect here.
     * We execute it in the provided scope.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun process(effect: AppEffect, scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            execute(effect, scope)
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private suspend fun execute(effect: AppEffect, scope: CoroutineScope) {
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
                AccNodeUtils.clickNode(effect.node.originalNode)
            }

            is AppEffect.PlayNotificationSound -> { /* Implementation */
            }

            is AppEffect.StartDash -> bubbleManager.startDash(effect.dashId)
            is AppEffect.EndDash -> bubbleManager.endDash()
            is AppEffect.StartOdometer -> odometerEffectHandler.startUp()
            is AppEffect.StopOdometer -> odometerEffectHandler.shutDown()

            is AppEffect.ProcessTipNotification -> tipEffectHandler.process(scope, effect)

            // --- LOOPBACKS (Produces Events) ---

            is AppEffect.EvaluateOffer -> {
                val result = offerEvaluator.evaluateOffer(effect.parsedOffer)

                // 1. Emit the Decision back to State Machine
                _events.emit(OfferEvaluationEvent(result.action))

                // 2. Show the Bubble (Side Effect)
                val persona = when (result.action) {
                    OfferAction.ACCEPT -> ChatPersona.GoodOffer
                    OfferAction.DECLINE -> ChatPersona.BadOffer
                    OfferAction.NOTHING -> ChatPersona.Inspector
                }
                bubbleManager.postMessage(result.message, persona)
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
                execute(effect.effect, scope) // Recursive
            }

            is AppEffect.SequentialEffect -> {
                effect.effects.forEach { child -> execute(child, scope) }
            }
        }
    }
}