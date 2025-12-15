package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.data.event.status.PickupStatus
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.reducers.MainReducer.Transition

object PickupReducer {

    // Helper to start pickup from Awaiting/Offer
    fun startPickup(
        dashId: String?,
        input: ScreenInfo.PickupDetails,
        odometer: Double?
    ): Transition {
        val storeName = input.storeName ?: "Unknown Store"
        val newState = AppStateV2.OnPickup(
            dashId = dashId,
            storeName = storeName,
            status = input.status,
            customerNameHash = input.customerNameHash
        )
        val event = ReducerUtils.createEvent(
            dashId,
            AppEventType.PICKUP_NAV_STARTED,
            ReducerUtils.gson.toJson(input),
            odometer
        )
        return Transition(
            newState,
            listOf(AppEffect.LogEvent(event), AppEffect.UpdateBubble("Heading to $storeName"))
        )
    }

    fun reduce(state: AppStateV2.OnPickup, input: ScreenInfo, context: StateContext): Transition {
        return when (input) {
            is ScreenInfo.PickupDetails -> {
                val resolvedStore = input.storeName ?: state.storeName
                val resolvedCustomer = input.customerNameHash ?: state.customerNameHash

                if (resolvedStore != state.storeName || resolvedCustomer != state.customerNameHash || input.status != state.status) {
                    val newState = state.copy(
                        storeName = resolvedStore,
                        customerNameHash = resolvedCustomer,
                        status = input.status
                    )
                    val effects = mutableListOf<AppEffect>()

                    if (input.status == PickupStatus.ARRIVED && state.status != PickupStatus.ARRIVED) {
                        val event = ReducerUtils.createEvent(
                            state.dashId,
                            AppEventType.PICKUP_ARRIVED,
                            ReducerUtils.gson.toJson(input),
                            context.odometerReading
                        )
                        effects.add(AppEffect.LogEvent(event))
                        effects.add(AppEffect.UpdateBubble("Arrived at $resolvedStore"))
                    }
                    Transition(newState, effects)
                } else {
                    Transition(state)
                }
            }
            // TODO: Transition to DeliveryReducer
            else -> Transition(state)
        }
    }
}