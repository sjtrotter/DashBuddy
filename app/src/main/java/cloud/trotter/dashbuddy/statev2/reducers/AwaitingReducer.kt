package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.Reducer
import java.util.UUID

object AwaitingReducer {

    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo.WaitingForOffer,
        isRecovery: Boolean
    ): Reducer.Transition {
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
        if (oldState is AppStateV2.IdleOffline || oldState is AppStateV2.Initializing || (isRecovery && oldState.dashId == null)) {
            val payload = mapOf(
                "source" to if (isRecovery) "recovery" else "interaction",
                "start_screen" to "WaitingForOffer"
            )

            val event = ReducerUtils.createEvent(dashId, AppEventType.DASH_START, payload)
            effects.add(AppEffect.LogEvent(event))
            effects.add(AppEffect.UpdateBubble("Dash Started!"))
        }

        return Reducer.Transition(newState, effects)
    }

    fun reduce(
        state: AppStateV2.AwaitingOffer,
        input: ScreenInfo
    ): Reducer.Transition? {
        return when (input) {
            is ScreenInfo.WaitingForOffer -> {
                if (state.currentSessionPay != input.currentDashPay || state.waitTimeEstimate != input.waitTimeEstimate) {
                    Reducer.Transition(
                        state.copy(
                            currentSessionPay = input.currentDashPay,
                            waitTimeEstimate = input.waitTimeEstimate
                        )
                    )
                } else Reducer.Transition(state)
            }

            is ScreenInfo.Offer -> OfferReducer.transitionTo(state, input, isRecovery = false)
            is ScreenInfo.IdleMap -> IdleReducer.transitionTo(state, input, isRecovery = false)
            else -> null
        }
    }
}