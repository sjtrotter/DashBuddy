package cloud.trotter.dashbuddy.state.factories

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.data.event.status.PickupStatus
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.model.Transition
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PickupStateFactory @Inject constructor() {

    fun createEntry(
        oldState: AppStateV2,
        input: ScreenInfo.PickupDetails,
        isRecovery: Boolean
    ): Transition {
        val safeStoreName = input.storeName ?: "Unknown"

        val newState = AppStateV2.OnPickup(
            dashId = oldState.dashId,
            storeName = safeStoreName,
            customerNameHash = input.customerNameHash,
            status = input.status
        )

        val effects = mutableListOf<AppEffect>()
        if (!isRecovery) {
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
                        payload
                    )
                )
            )

            val persona = determinePersona(input.status, safeStoreName, input.customerNameHash)
            effects.add(AppEffect.UpdateBubble("Pickup: $safeStoreName", persona))
        }

        return Transition(newState, effects)
    }

    // Shared helper logic
    private fun determinePersona(
        status: PickupStatus,
        storeName: String,
        customerHash: String?
    ): ChatPersona {
        return when (status) {
            PickupStatus.NAVIGATING -> ChatPersona.Navigator
            PickupStatus.ARRIVED -> ChatPersona.Merchant(storeName)
            PickupStatus.CONFIRMED -> ChatPersona.Customer(customerHash?.take(6) ?: "Customer")
            PickupStatus.SHOPPING -> ChatPersona.Shopper
            else -> ChatPersona.Dispatcher
        }
    }
}