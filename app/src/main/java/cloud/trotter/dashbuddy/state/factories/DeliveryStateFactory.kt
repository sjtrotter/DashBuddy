package cloud.trotter.dashbuddy.state.factories

import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.model.Transition
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeliveryStateFactory @Inject constructor() {

    fun createEntry(
        oldState: AppStateV2,
        input: ScreenInfo.DropoffDetails,
        isRecovery: Boolean
    ): Transition {
        val storeName = (oldState as? AppStateV2.OnPickup)?.storeName

        val newState = AppStateV2.OnDelivery(
            dashId = oldState.dashId,
            storeName = storeName,
            customerNameHash = input.customerNameHash,
            customerAddressHash = input.customerAddressHash,
            deliveryDeadline = input.deadline
        )

        Timber.i("🚗 DELIVERY: en route to customer [${input.customerNameHash?.take(6) ?: "?"}]")

        val effects = mutableListOf<AppEffect>()

        // Always resume — idempotent, and on recovery we want GPS running if we're mid-delivery.
        effects.add(AppEffect.ResumeOdometer)

        if (!isRecovery) {
            val payload = mapOf(
                "status" to input.status,
                "customerHash" to input.customerNameHash,
                "addressHash" to input.customerAddressHash
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