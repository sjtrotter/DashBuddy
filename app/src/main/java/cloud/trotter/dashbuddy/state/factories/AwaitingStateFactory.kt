package cloud.trotter.dashbuddy.state.factories

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.model.Transition
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AwaitingStateFactory @Inject constructor() {

    fun createEntry(
        oldState: AppStateV2,
        input: ScreenInfo.WaitingForOffer,
        isRecovery: Boolean
    ): Transition {
        // Reuse ID if valid, else generate new
        val dashId = oldState.dashId ?: UUID.randomUUID().toString()

        val newState = AppStateV2.AwaitingOffer(
            dashId = dashId,
            currentSessionPay = input.currentDashPay,
            waitTimeEstimate = input.waitTimeEstimate,
            isHeadingBackToZone = input.isHeadingBackToZone
        )

        val effects = mutableListOf<AppEffect>()

        // Log DASH_START only if we are coming from offline or initializing
        // (Or recovering into a null ID state)
        if (oldState is AppStateV2.IdleOffline ||
            oldState is AppStateV2.Initializing ||
            (isRecovery && oldState.dashId == null)
        ) {
            val payload = mapOf(
                "source" to if (isRecovery) "recovery" else "interaction",
                "start_screen" to "WaitingForOffer"
            )

            // Note: We need a new Effect for "BubbleManager.startDash(id)" if strictly pure,
            // or we assume LogEvent(DASH_START) covers it in the handler.
            // For now, sticking to standard effects:
            effects.add(
                AppEffect.LogEvent(
                    ReducerUtils.createEvent(
                        dashId,
                        AppEventType.DASH_START,
                        payload
                    )
                )
            )
            effects.add(AppEffect.StartOdometer)
            effects.add(AppEffect.UpdateBubble("Dash Started!", ChatPersona.Dispatcher))
        }

        return Transition(newState, effects)
    }
}