package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.Reducer
import cloud.trotter.dashbuddy.state.reducers.postdelivery.PostDeliveryReducer
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class DeliveryReducer @Inject constructor(
    private val awaitingReducerProvider: Provider<AwaitingReducer>,
    private val dashPausedReducer: DashPausedReducer,
    private val postDeliveryReducerProvider: Provider<PostDeliveryReducer>,
) {

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
            customerAddressHash = null
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

            // it looks like in practice sometimes the app goes to the awaiting offer state
            // before it makes it to post delivery.
            is ScreenInfo.WaitingForOffer -> {
                awaitingReducerProvider.get().transitionTo(state, input, isRecovery = false)
            }

            is ScreenInfo.DeliverySummaryCollapsed -> {
                postDeliveryReducerProvider.get().transitionTo(state, input, isRecovery = false)
            }

            is ScreenInfo.DeliveryCompleted -> {
                // ARRIVED -> COMPLETED
                postDeliveryReducerProvider.get().transitionTo(state, input, isRecovery = false)
            }
            // Note: If user cancels, we might see IdleMap or WaitingForOffer.
            // The Main Reducer's Anchor logic will catch that.

            is ScreenInfo.DashPaused -> dashPausedReducer.transitionTo(
                state,
                input,
                isRecovery = false
            )

            else -> null
        }
    }
}