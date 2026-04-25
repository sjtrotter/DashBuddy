package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.state.ScreenUpdateEvent
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.factories.AwaitingStateFactory
import cloud.trotter.dashbuddy.state.factories.DashPausedStateFactory
import cloud.trotter.dashbuddy.state.factories.PostDeliveryStateFactory
import cloud.trotter.dashbuddy.state.model.Transition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeliveryReducer @Inject constructor(
    private val awaitingStateFactory: AwaitingStateFactory,
    private val dashPausedStateFactory: DashPausedStateFactory,
    private val postDeliveryStateFactory: PostDeliveryStateFactory,
) {

    fun reduce(state: AppStateV2.OnDelivery, event: StateEvent): Transition? {
        return when (event) {
            is ScreenUpdateEvent -> {
                val input = event.screenInfo ?: return null
                handleScreenUpdate(state, input)
            }

            else -> null
        }
    }

    private fun handleScreenUpdate(state: AppStateV2.OnDelivery, input: ScreenInfo): Transition? {
        fun request(result: Transition) = result

        return when (input) {
            is ScreenInfo.DropoffDetails -> {
                // Internal update: enrich fields as more data becomes available
                val deadlineChanged = input.deadline != null && input.deadline != state.deliveryDeadline
                val customerChanged = input.customerNameHash != null && input.customerNameHash != state.customerNameHash
                val addressChanged = input.customerAddressHash != null && input.customerAddressHash != state.customerAddressHash
                if (deadlineChanged || customerChanged || addressChanged) {
                    Transition(
                        state.copy(
                            deliveryDeadline = input.deadline ?: state.deliveryDeadline,
                            customerNameHash = input.customerNameHash ?: state.customerNameHash,
                            customerAddressHash = input.customerAddressHash ?: state.customerAddressHash
                        )
                    )
                } else {
                    Transition(state)
                }
            }

            is ScreenInfo.WaitingForOffer -> request(
                awaitingStateFactory.createEntry(state, input, isRecovery = false)
            )

            // Unified DeliverySummary
            is ScreenInfo.DeliverySummary -> request(
                postDeliveryStateFactory.createEntry(state, input, isRecovery = false)
            )

            is ScreenInfo.DashPaused -> request(
                dashPausedStateFactory.createEntry(state, input, isRecovery = false)
            )

            else -> null
        }
    }
}