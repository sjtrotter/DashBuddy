package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.data.event.status.PickupStatus
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.Reducer
import cloud.trotter.dashbuddy.state.reducers.offer.OfferReducer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PickupReducer @Inject constructor(
    private val awaitingReducer: AwaitingReducer,
    private val deliveryReducer: DeliveryReducer,
    private val offerReducer: OfferReducer,
) {

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
            val persona = when (input.status) {
                PickupStatus.NAVIGATING -> ChatPersona.Navigator
                PickupStatus.ARRIVED -> ChatPersona.Merchant(safeStoreName)
                PickupStatus.CONFIRMED -> ChatPersona.Customer(
                    input.customerNameHash?.take(6) ?: "Customer"
                )

                PickupStatus.SHOPPING -> ChatPersona.Shopper
                else -> ChatPersona.Dispatcher
            }
            effects.add(AppEffect.UpdateBubble("Pickup: $safeStoreName", persona))
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
                    val storeName = if (hasStoreChanged) newStoreName else state.storeName

                    val nextState = state.copy(
                        storeName = storeName,
                        status = input.status
                    )

                    val effects = mutableListOf<AppEffect>()

                    // FIX 2: Trigger Bubble Update
                    val persona = when (input.status) {
                        PickupStatus.NAVIGATING -> ChatPersona.Navigator
                        PickupStatus.ARRIVED -> ChatPersona.Merchant(storeName)
                        PickupStatus.CONFIRMED -> ChatPersona.Customer(
                            input.customerNameHash?.take(6) ?: "Customer"
                        )

                        PickupStatus.SHOPPING -> ChatPersona.Shopper
                        else -> ChatPersona.Dispatcher
                    }
                    effects.add(AppEffect.UpdateBubble("Pickup: ${nextState.storeName}", persona))

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
                deliveryReducer.transitionTo(state, input, isRecovery = false)
            }

            is ScreenInfo.WaitingForOffer -> {
                awaitingReducer.transitionTo(state, input, isRecovery = false)
            }

            is ScreenInfo.Offer -> {
                offerReducer.transitionTo(state, input, isRecovery = false)
            }

            else -> null
        }
    }
}