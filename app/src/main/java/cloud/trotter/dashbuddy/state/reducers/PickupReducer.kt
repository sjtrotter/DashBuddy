package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.data.event.status.PickupStatus
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.event.ScreenUpdateEvent
import cloud.trotter.dashbuddy.state.event.StateEvent
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

    // --- NEW CONTRACT: Handle ANY StateEvent ---
    fun reduce(state: AppStateV2.OnPickup, event: StateEvent): Transition? {
        return when (event) {
            is ScreenUpdateEvent -> {
                val input = event.screenInfo ?: return null
                handleScreenUpdate(state, input)
            }
            // Pickup state usually handles internal clicks for "Arrived", etc.
            // You can add `is ClickEvent` here later if needed.
            else -> null
        }
    }

    private fun handleScreenUpdate(state: AppStateV2.OnPickup, input: ScreenInfo): Transition? {

        // Helper: Simple forwarder (no wrapping needed)
        fun request(result: Transition) = result

        return when (input) {
            is ScreenInfo.PickupDetails -> {
                // Internal Logic: Check for meaningful updates (Status change / Store Name resolution)
                // Note: 'input.storeName' might be null if not found, fallback to existing state
                val newStoreName = input.storeName ?: state.storeName

                val hasStoreChanged = newStoreName != state.storeName && newStoreName != "Unknown"
                val hasStatusChanged = input.status != state.status

                if (hasStoreChanged || hasStatusChanged) {
                    val nextStoreName = if (hasStoreChanged) newStoreName else state.storeName
                    val nextState = state.copy(storeName = nextStoreName, status = input.status)
                    val effects = mutableListOf<AppEffect>()

                    // Persona Selection
                    val persona = when (input.status) {
                        PickupStatus.NAVIGATING -> ChatPersona.Navigator
                        PickupStatus.ARRIVED -> ChatPersona.Merchant(nextStoreName)
                        PickupStatus.CONFIRMED -> ChatPersona.Customer(
                            // input.customerNameHash is not in PickupDetails anymore in some versions,
                            // check your ScreenInfo definition. Assuming it exists:
                            input.customerNameHash?.take(6) ?: "Customer"
                        )

                        PickupStatus.SHOPPING -> ChatPersona.Shopper
                        else -> ChatPersona.Dispatcher
                    }

                    effects.add(AppEffect.UpdateBubble("Pickup: ${nextState.storeName}", persona))

                    // Logging Effects
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
                                    state.dashId,
                                    AppEventType.PICKUP_NAV_STARTED, // Or PICKUP_STATUS_UPDATE per our discussion
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