package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.event.ScreenUpdateEvent
import cloud.trotter.dashbuddy.state.event.StateEvent
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
                // Internal update (e.g. Navigation instructions changing)
                Transition(state)
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