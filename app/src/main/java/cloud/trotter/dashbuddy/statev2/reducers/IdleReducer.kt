package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.reducers.MainReducer.Transition
import java.util.UUID

object IdleReducer {
    fun reduce(
        state: AppStateV2.IdleOffline,
        input: ScreenInfo,
        context: StateContext
    ): Transition {
        return when (input) {
            // Zone Update
            is ScreenInfo.IdleMap -> {
                if ((state.lastKnownZone != input.zoneName && input.zoneName != null) ||
                    state.dashType != input.dashType
                ) {
                    Transition(
                        state.copy(
                            lastKnownZone = input.zoneName ?: state.lastKnownZone,
                            dashType = input.dashType
                        )
                    )
                } else {
                    Transition(state)
                }
            }
            // START DASH
            is ScreenInfo.WaitingForOffer -> {
                val newDashId = UUID.randomUUID().toString()

                val newState = AppStateV2.AwaitingOffer(
                    dashId = newDashId,
                    currentSessionPay = input.currentDashPay,
                    waitTimeEstimate = input.waitTimeEstimate,
                    isHeadingBackToZone = input.isHeadingBackToZone
                )

                val startPayload = mapOf(
                    "zone" to state.lastKnownZone,
                    "type" to state.dashType,
                    "start_screen" to "WaitingForOffer"
                )

                val startEvent = ReducerUtils.createEvent(
                    newDashId,
                    AppEventType.DASH_START,
                    ReducerUtils.gson.toJson(startPayload),
                    context.odometerReading
                )

                Transition(
                    newState,
                    listOf(
                        AppEffect.LogEvent(startEvent),
                        AppEffect.UpdateBubble("Dash Started! Good luck.")
                    )
                )
            }

            else -> Transition(state)
        }
    }
}