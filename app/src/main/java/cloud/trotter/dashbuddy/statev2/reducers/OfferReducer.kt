package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.reducers.MainReducer.Transition

object OfferReducer {
    fun reduce(
        state: AppStateV2,
        input: ScreenInfo,
        context: StateContext
    ): Transition {
        return when (input) {
            // ACCEPTED
            is ScreenInfo.PickupDetails -> {
                PickupReducer.startPickup(state.dashId, input, context.odometerReading)
            }
            // DECLINED / TIMEOUT
            is ScreenInfo.IdleMap -> {
                // Back to Awaiting (Dash is likely still active)
                Transition(
                    AppStateV2.AwaitingOffer(
                        dashId = state.dashId,
                        isHeadingBackToZone = false
                    )
                )
            }
            // If we see WaitingForOffer again, we definitely declined/timed out
            is ScreenInfo.WaitingForOffer -> {
                Transition(
                    AppStateV2.AwaitingOffer(
                        dashId = state.dashId,
                        currentSessionPay = input.currentDashPay,
                        isHeadingBackToZone = input.isHeadingBackToZone
                    )
                )
            }

            else -> Transition(state)
        }
    }
}