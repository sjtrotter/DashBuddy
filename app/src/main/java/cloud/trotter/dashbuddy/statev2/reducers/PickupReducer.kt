package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.Reducer

object PickupReducer {

    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo.PickupDetails,
        isRecovery: Boolean
    ): Reducer.Transition {
        val newState = AppStateV2.OnPickup(
            dashId = oldState.dashId,
            storeName = input.storeName ?: "Unknown",
            customerNameHash = input.customerNameHash,
            status = input.status
        )

        val effects = mutableListOf<AppEffect>()
        if (!isRecovery) {
            effects.add(
                AppEffect.LogEvent(
                    ReducerUtils.createEvent(
                        oldState.dashId,
                        AppEventType.PICKUP_NAV_STARTED,
                        "Pickup Started"
                    )
                )
            )
            effects.add(AppEffect.UpdateBubble("Pickup: ${input.storeName}"))
        }

        return Reducer.Transition(newState, effects)
    }

    fun reduce(state: AppStateV2.OnPickup, input: ScreenInfo): Reducer.Transition? {
        return when (input) {
            is ScreenInfo.PickupDetails -> {
                if (state.status != input.status) {
                    Reducer.Transition(state.copy(status = input.status))
                } else Reducer.Transition(state)
            }
            // SUCCESS: Transition to Delivery
            is ScreenInfo.DropoffDetails -> {
                DeliveryReducer.transitionTo(state, input, isRecovery = false)
            }

            is ScreenInfo.WaitingForOffer -> {
                // Order cancelled or dasher unassigned
                AwaitingReducer.transitionTo(state, input, isRecovery = false)
            }

            is ScreenInfo.Offer -> {
                // Offer popup while on pickup screens
                OfferReducer.transitionTo(state, input, isRecovery = false)
            }

            else -> null
        }
    }
}