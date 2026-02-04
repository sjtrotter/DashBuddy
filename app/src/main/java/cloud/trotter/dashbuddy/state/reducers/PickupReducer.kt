package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.data.event.status.PickupStatus
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.factories.AwaitingStateFactory
import cloud.trotter.dashbuddy.state.factories.DeliveryStateFactory
import cloud.trotter.dashbuddy.state.factories.OfferStateFactory
import cloud.trotter.dashbuddy.state.model.Transition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PickupReducer @Inject constructor(
    private val deliveryStateFactory: DeliveryStateFactory,
    private val awaitingStateFactory: AwaitingStateFactory,
    private val offerStateFactory: OfferStateFactory,
) {

    fun reduce(state: AppStateV2.OnPickup, input: ScreenInfo): Transition? {

        // Helper: Simple forwarder (no wrapping needed)
        fun request(result: Transition) = result

        return when (input) {
            is ScreenInfo.PickupDetails -> {
                // Internal Logic: Check for meaningful updates (Status change / Store Name resolution)
                val newStoreName = input.storeName ?: state.storeName
                val hasStoreChanged = newStoreName != state.storeName && newStoreName != "Unknown"
                val hasStatusChanged = input.status != state.status

                if (hasStoreChanged || hasStatusChanged) {
                    val storeName = if (hasStoreChanged) newStoreName else state.storeName
                    val nextState = state.copy(storeName = storeName, status = input.status)
                    val effects = mutableListOf<AppEffect>()

                    // Internal Effects
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

                    if (hasStatusChanged && input.status == PickupStatus.ARRIVED) {
                        effects.add(
                            AppEffect.LogEvent(
                                ReducerUtils.createEvent(
                                    state.dashId,
                                    AppEventType.PICKUP_ARRIVED,
                                    mapOf("storeName" to nextState.storeName)
                                )
                            )
                        )
                    } else if (hasStoreChanged) {
                        effects.add(
                            AppEffect.LogEvent(
                                ReducerUtils.createEvent(
                                    state.dashId, AppEventType.PICKUP_NAV_STARTED,
                                    mapOf(
                                        "message" to "Store Name Updated",
                                        "previous" to state.storeName,
                                        "updated" to nextState.storeName
                                    )
                                )
                            )
                        )
                    }

                    // Direct Transition (same state class, updated data)
                    Transition(nextState, effects)
                } else {
                    Transition(state)
                }
            }

            is ScreenInfo.DropoffDetails -> request(
                deliveryStateFactory.createEntry(state, input, isRecovery = false)
            )

            is ScreenInfo.WaitingForOffer -> request(
                awaitingStateFactory.createEntry(state, input, isRecovery = false)
            )

            is ScreenInfo.Offer -> request(
                offerStateFactory.createEntry(state, input, isRecovery = false)
            )

            else -> null
        }
    }
}