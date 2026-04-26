package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import cloud.trotter.dashbuddy.domain.model.state.ScreenUpdateEvent
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.factories.AwaitingStateFactory
import cloud.trotter.dashbuddy.state.factories.DeliveryStateFactory
import cloud.trotter.dashbuddy.state.factories.OfferStateFactory
import cloud.trotter.dashbuddy.state.model.Transition
import timber.log.Timber
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
                handleScreenUpdate(state, input, event.odometer)
            }
            // Pickup state usually handles internal clicks for "Arrived", etc.
            // You can add `is ClickEvent` here later if needed.
            else -> null
        }
    }

    private fun handleScreenUpdate(
        state: AppStateV2.OnPickup,
        input: ScreenInfo,
        odometer: Double?
    ): Transition? {

        // Helper: Simple forwarder (no wrapping needed)
        fun request(result: Transition) = result

        return when (input) {
            is ScreenInfo.PickupDetails -> {
                // Internal Logic: Check for meaningful updates (Status change / Store Name resolution)
                // Note: 'input.storeName' might be null if not found, fallback to existing state
                val newStoreName = input.storeName ?: state.storeName

                val hasStoreChanged = newStoreName != state.storeName && newStoreName != "Unknown"
                val hasStatusChanged = input.status != state.status
                val hasDeadlineChanged = input.deadline != state.pickupDeadline
                val hasItemCountChanged = input.itemCount != state.itemCount
                val hasRedCardChanged = input.redCardTotal != state.redCardTotal

                if (hasStoreChanged || hasStatusChanged || hasDeadlineChanged || hasItemCountChanged || hasRedCardChanged) {
                    val nextStoreName = if (hasStoreChanged) newStoreName else state.storeName
                    // Treat a direct NAVIGATING→SHOPPING transition (Shop & Deliver) the same as ARRIVED:
                    // set arrivedAt and odometerAtArrival if not already set.
                    val justArrived = hasStatusChanged && (
                        input.status == PickupStatus.ARRIVED ||
                        (input.status == PickupStatus.SHOPPING && state.arrivedAt == null)
                    )
                    val arrivedAt = if (justArrived) System.currentTimeMillis() else state.arrivedAt
                    val nextState = state.copy(
                        storeName = nextStoreName,
                        status = input.status,
                        pickupDeadline = input.deadline ?: state.pickupDeadline,
                        arrivedAt = arrivedAt,
                        itemCount = input.itemCount ?: state.itemCount,
                        redCardTotal = input.redCardTotal ?: state.redCardTotal,
                        odometerAtArrival = if (justArrived && state.odometerAtArrival == null) odometer else state.odometerAtArrival
                    )
                    if (hasStatusChanged) Timber.i("🛍️ PICKUP STATUS: ${state.status} → ${input.status} @ $nextStoreName")
                    val effects = mutableListOf<AppEffect>()

                    // Only post a chat message when the status or store actually changes — not on
                    // silent data refreshes (deadline, item count, red card total).
                    if (hasStatusChanged || hasStoreChanged) {
                        // Persona Selection
                        val persona = when (input.status) {
                            PickupStatus.NAVIGATING -> ChatPersona.Navigator
                            PickupStatus.ARRIVED -> ChatPersona.Merchant(nextStoreName)
                            PickupStatus.CONFIRMED -> ChatPersona.Customer(
                                input.customerNameHash?.take(6) ?: "Customer"
                            )
                            PickupStatus.SHOPPING -> ChatPersona.Shopper
                            else -> ChatPersona.Dispatcher
                        }
                        effects.add(AppEffect.UpdateBubble("Pickup: ${nextState.storeName}", persona))
                    }

                    // Logging Effects
                    if (justArrived) {
                        effects.add(AppEffect.PauseOdometer)
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
                deliveryStateFactory.createEntry(state, input, isRecovery = false, odometerMiles = odometer)
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