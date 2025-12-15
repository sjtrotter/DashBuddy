package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.reducers.MainReducer.Transition

object AwaitingReducer {
    fun reduce(
        state: AppStateV2.AwaitingOffer,
        input: ScreenInfo,
        context: StateContext
    ): Transition {
        return when (input) {
            // Live Updates
            is ScreenInfo.WaitingForOffer -> {
                if (state.currentSessionPay != input.currentDashPay ||
                    state.waitTimeEstimate != input.waitTimeEstimate ||
                    state.isHeadingBackToZone != input.isHeadingBackToZone
                ) {
                    Transition(
                        state.copy(
                            currentSessionPay = input.currentDashPay,
                            waitTimeEstimate = input.waitTimeEstimate,
                            isHeadingBackToZone = input.isHeadingBackToZone
                        )
                    )
                } else {
                    Transition(state)
                }
            }
            // OFFER RECEIVED
            is ScreenInfo.Offer -> {
                val newState = AppStateV2.OfferPresented(
                    dashId = state.dashId,
                    rawOfferText = input.parsedOffer.rawExtractedTexts,
                    merchantName = input.parsedOffer.orders.joinToString { it.storeName },
                    amount = input.parsedOffer.payAmount
                )
                val event = ReducerUtils.createEvent(
                    state.dashId,
                    AppEventType.OFFER_RECEIVED,
                    ReducerUtils.gson.toJson(input.parsedOffer),
                    context.odometerReading
                )
                Transition(
                    newState,
                    listOf(
                        AppEffect.LogEvent(event),
                        AppEffect.CaptureScreenshot("offer_${input.parsedOffer.offerHash}"),
                        AppEffect.UpdateBubble("Offer: $${input.parsedOffer.payAmount}")
                    )
                )
            }
            // END DASH
            is ScreenInfo.IdleMap -> {
                val endEvent = ReducerUtils.createEvent(
                    state.dashId,
                    AppEventType.DASH_STOP,
                    "Return to Map",
                    context.odometerReading
                )
                Transition(
                    AppStateV2.IdleOffline(lastKnownZone = null),
                    listOf(AppEffect.LogEvent(endEvent), AppEffect.UpdateBubble("Dash Ended"))
                )
            }
            // DIRECT PICKUP (Blind Accept / Restore)
            is ScreenInfo.PickupDetails -> {
                PickupReducer.startPickup(state.dashId, input, context.odometerReading)
            }

            else -> Transition(state)
        }
    }
}