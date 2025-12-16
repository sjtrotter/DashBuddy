package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.Reducer

object DeliveryReducer {

    // --- FACTORY (Entry Point) ---
    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo.DropoffDetails,
        isRecovery: Boolean
    ): Reducer.Transition {
        val newState = AppStateV2.OnDelivery(
            dashId = oldState.dashId,
            // We initialize with nulls because hashes aren't readable text.
            // In the future, we can look up real names from the DB using the hash.
            customerNameHash = null,
            customerAddresHash = null
        )

        val effects = mutableListOf<AppEffect>()

        // LOGIC: If we are recovering, we don't want to log "Picked Up" again.
        if (!isRecovery) {
            val payload = mapOf(
                "status" to input.status,
                "customerHash" to input.customerNameHash,
                "addressHash" to input.addressHash
            )

            // Log: ORDER_PICKED_UP (Signals start of drive to customer)
            effects.add(
                AppEffect.LogEvent(
                    ReducerUtils.createEvent(
                        dashId = oldState.dashId,
                        type = AppEventType.PICKUP_CONFIRMED,
                        payload = payload
                    )
                )
            )
            effects.add(AppEffect.UpdateBubble("Heading to Customer"))
        }

        return Reducer.Transition(newState, effects)
    }

    // --- REDUCER (Behavior) ---
    fun reduce(
        state: AppStateV2.OnDelivery,
        input: ScreenInfo
    ): Reducer.Transition? {
        return when (input) {
            is ScreenInfo.DropoffDetails -> {
                // We are still driving.
                // Currently, DropoffDetails doesn't change much, but if status updates, we could track it.
                Reducer.Transition(state)
            }

            is ScreenInfo.DeliveryCompleted -> {
                // ARRIVED -> COMPLETED
                PostDeliveryReducer.transitionTo(state, input, isRecovery = false)
            }
            // Note: If user cancels, we might see IdleMap or WaitingForOffer.
            // The Main Reducer's Anchor logic will catch that.
            else -> null
        }
    }
}