package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.data.event.status.PickupStatus
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
        // Fallback to "Unknown" if storeName is missing (common in some nav screens)
        val safeStoreName = input.storeName ?: "Unknown"

        val newState = AppStateV2.OnPickup(
            dashId = oldState.dashId,
            storeName = safeStoreName,
            customerNameHash = input.customerNameHash,
            status = input.status
        )

        val effects = mutableListOf<AppEffect>()
        if (!isRecovery) {
            // FIX 1: Log the actual store name, not just "Pickup Started"
            val payload = mapOf(
                "message" to "Pickup Started",
                "storeName" to safeStoreName,
                "status" to input.status
            )

            effects.add(
                AppEffect.LogEvent(
                    ReducerUtils.createEvent(
                        oldState.dashId,
                        AppEventType.PICKUP_NAV_STARTED,
                        payload // Log the real data!
                    )
                )
            )
            effects.add(AppEffect.UpdateBubble("Pickup: $safeStoreName"))
        }

        return Reducer.Transition(newState, effects)
    }

    fun reduce(state: AppStateV2.OnPickup, input: ScreenInfo): Reducer.Transition? {
        return when (input) {
            is ScreenInfo.PickupDetails -> {
                val newStoreName = input.storeName ?: state.storeName
                val hasStoreChanged = newStoreName != state.storeName && newStoreName != "Unknown"
                val hasStatusChanged = input.status != state.status

                if (hasStoreChanged || hasStatusChanged) {
                    // Update State
                    val nextState = state.copy(
                        storeName = if (hasStoreChanged) newStoreName else state.storeName,
                        status = input.status
                    )

                    val effects = mutableListOf<AppEffect>()

                    // FIX 2: Trigger Bubble Update
                    effects.add(AppEffect.UpdateBubble("Pickup: ${nextState.storeName}"))

                    // FIX 3: Log Significant Changes to Database
                    if (hasStatusChanged && input.status == PickupStatus.ARRIVED) {
                        effects.add(
                            AppEffect.LogEvent(
                                ReducerUtils.createEvent(
                                    state.dashId,
                                    AppEventType.PICKUP_ARRIVED, // Make sure this exists in AppEventType!
                                    mapOf("storeName" to nextState.storeName)
                                )
                            )
                        )
                    } else if (hasStoreChanged) {
                        // Log that we found the "Real" store name after the initial "(000)..." nav screen
                        effects.add(
                            AppEffect.LogEvent(
                                ReducerUtils.createEvent(
                                    state.dashId,
                                    AppEventType.PICKUP_NAV_STARTED, // Re-logging with corrected name
                                    mapOf(
                                        "message" to "Store Name Updated",
                                        "previous" to state.storeName,
                                        "updated" to nextState.storeName
                                    )
                                )
                            )
                        )
                    }

                    Reducer.Transition(nextState, effects)
                } else {
                    Reducer.Transition(state)
                }
            }

            // SUCCESS: Transition to Delivery
            is ScreenInfo.DropoffDetails -> {
                DeliveryReducer.transitionTo(state, input, isRecovery = false)
            }

            is ScreenInfo.WaitingForOffer -> {
                AwaitingReducer.transitionTo(state, input, isRecovery = false)
            }

            is ScreenInfo.Offer -> {
                OfferReducer.transitionTo(state, input, isRecovery = false)
            }

            else -> null
        }
    }
}