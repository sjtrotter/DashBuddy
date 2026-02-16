package cloud.trotter.dashbuddy.state.factories

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.model.Transition
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeliveryStateFactory @Inject constructor() {

    fun createEntry(
        oldState: AppStateV2,
        input: ScreenInfo.DropoffDetails,
        isRecovery: Boolean
    ): Transition {
        val newState = AppStateV2.OnDelivery(
            dashId = oldState.dashId,
            customerNameHash = null,
            customerAddressHash = null
        )

        val effects = mutableListOf<AppEffect>()

        if (!isRecovery) {
            val payload = mapOf(
                "status" to input.status,
                "customerHash" to input.customerNameHash,
                "addressHash" to input.addressHash
            )

            effects.add(
                AppEffect.LogEvent(
                    ReducerUtils.createEvent(
                        dashId = oldState.dashId,
                        type = AppEventType.PICKUP_CONFIRMED,
                        payload = payload
                    )
                )
            )
            val customer = input.customerNameHash?.take(6) ?: "Customer"
            val persona = ChatPersona.Customer(customer)
            effects.add(AppEffect.UpdateBubble("Heading to $customer", persona))
        }

        return Transition(newState, effects)
    }
}