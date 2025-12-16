package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.Reducer

object OfferReducer {

    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo.Offer,
        isRecovery: Boolean
    ): Reducer.Transition {
        val newState = AppStateV2.OfferPresented(
            dashId = oldState.dashId,
            rawOfferText = input.parsedOffer.rawExtractedTexts,
            merchantName = input.parsedOffer.orders.joinToString { it.storeName },
            amount = input.parsedOffer.payAmount
        )

        val effects = mutableListOf<AppEffect>()
        if (!isRecovery) {
            effects.add(
                AppEffect.LogEvent(
                    ReducerUtils.createEvent(
                        oldState.dashId,
                        AppEventType.OFFER_RECEIVED,
                        input.parsedOffer
                    )
                )
            )
            effects.add(AppEffect.CaptureScreenshot("offer_${System.currentTimeMillis()}"))
        }

        return Reducer.Transition(newState, effects)
    }

    fun reduce(state: AppStateV2.OfferPresented, input: ScreenInfo): Reducer.Transition? {
        return when (input) {
            is ScreenInfo.Offer -> Reducer.Transition(state) // No change
            is ScreenInfo.PickupDetails -> PickupReducer.transitionTo(
                state,
                input,
                isRecovery = false
            )

            is ScreenInfo.WaitingForOffer -> {
                // DECLINED / TIMEOUT
                val transition = AwaitingReducer.transitionTo(state, input, isRecovery = false)
                // Append explicit decline log
                val declineEvent = ReducerUtils.createEvent(
                    state.dashId,
                    AppEventType.OFFER_DECLINED,
                    "Returned to search"
                )
                transition.copy(effects = transition.effects + AppEffect.LogEvent(declineEvent))
            }

            else -> null
        }
    }
}