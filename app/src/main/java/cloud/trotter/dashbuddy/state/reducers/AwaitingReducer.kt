package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.event.ScreenUpdateEvent
import cloud.trotter.dashbuddy.state.event.StateEvent
import cloud.trotter.dashbuddy.state.factories.DashPausedStateFactory
import cloud.trotter.dashbuddy.state.factories.DeliveryStateFactory
import cloud.trotter.dashbuddy.state.factories.IdleStateFactory
import cloud.trotter.dashbuddy.state.factories.OfferStateFactory
import cloud.trotter.dashbuddy.state.factories.PickupStateFactory
import cloud.trotter.dashbuddy.state.factories.PostDeliveryStateFactory
import cloud.trotter.dashbuddy.state.factories.SummaryStateFactory
import cloud.trotter.dashbuddy.state.model.Transition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AwaitingReducer @Inject constructor(
    private val idleStateFactory: IdleStateFactory,
    private val offerStateFactory: OfferStateFactory,
    private val pickupStateFactory: PickupStateFactory,
    private val deliveryStateFactory: DeliveryStateFactory,
    private val postDeliveryStateFactory: PostDeliveryStateFactory,
    private val summaryStateFactory: SummaryStateFactory,
    private val dashPausedStateFactory: DashPausedStateFactory
) {

    // --- NEW CONTRACT: Handle ANY StateEvent ---
    fun reduce(
        state: AppStateV2.AwaitingOffer,
        event: StateEvent
    ): Transition? {
        return when (event) {
            is ScreenUpdateEvent -> {
                val input = event.screenInfo ?: return null
                handleScreenUpdate(state, input)
            }
            // Awaiting state generally doesn't handle Clicks/Timeouts directly,
            // but the structure is here if you need it later.
            else -> null
        }
    }

    private fun handleScreenUpdate(
        state: AppStateV2.AwaitingOffer,
        input: ScreenInfo
    ): Transition? {

        // Helper: Factories now return Transition directly. We just pass it through.
        fun request(factoryResult: Transition) = factoryResult

        return when (input) {
            // Internal Update
            is ScreenInfo.WaitingForOffer -> {
                if (state.currentSessionPay != input.currentDashPay || state.waitTimeEstimate != input.waitTimeEstimate) {
                    Transition(
                        state.copy(
                            currentSessionPay = input.currentDashPay,
                            waitTimeEstimate = input.waitTimeEstimate
                        )
                    )
                } else Transition(state)
            }

            // Transitions (Just return the factory result!)
            is ScreenInfo.Offer -> request(
                offerStateFactory.createEntry(state, input, isRecovery = false)
            )

            is ScreenInfo.IdleMap -> request(
                idleStateFactory.createEntry(state, input, isRecovery = false)
            )

            is ScreenInfo.DashPaused -> request(
                dashPausedStateFactory.createEntry(state, input, isRecovery = false)
            )

            // UPDATED: Use Unified DeliverySummary
            is ScreenInfo.DeliverySummary -> request(
                postDeliveryStateFactory.createEntry(state, input, isRecovery = false)
            )

            is ScreenInfo.PickupDetails -> request(
                pickupStateFactory.createEntry(state, input, isRecovery = false)
            )

            is ScreenInfo.DropoffDetails -> request(
                deliveryStateFactory.createEntry(state, input, isRecovery = false)
            )

            is ScreenInfo.DashSummary -> request(
                summaryStateFactory.createEntry(state, input, isRecovery = false)
            )

            else -> null
        }
    }
}