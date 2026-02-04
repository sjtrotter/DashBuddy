package cloud.trotter.dashbuddy.state.reducers.offer

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.factories.AwaitingStateFactory
import cloud.trotter.dashbuddy.state.factories.DashPausedStateFactory
import cloud.trotter.dashbuddy.state.factories.OfferStateFactory
import cloud.trotter.dashbuddy.state.factories.PickupStateFactory
import cloud.trotter.dashbuddy.state.model.Transition
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfferReducer @Inject constructor(
    private val offerStateFactory: OfferStateFactory,
    private val awaitingStateFactory: AwaitingStateFactory,
    private val pickupStateFactory: PickupStateFactory,
    private val dashPausedStateFactory: DashPausedStateFactory,
) {

    fun reduce(state: AppStateV2.OfferPresented, input: ScreenInfo): Transition? {
        // Helper: Takes the factory result and adds any extra local effects
        fun request(
            factoryResult: Transition,
            extraEffects: List<AppEffect> = emptyList()
        ): Transition {
            return factoryResult.copy(
                effects = factoryResult.effects + extraEffects
            )
        }

        return when (input) {
            is ScreenInfo.Offer -> {
                // LOGIC: Refresh if data changed
                if (input.parsedOffer.offerHash != state.currentOfferHash) {
                    val result = offerStateFactory.createEntry(state, input, isRecovery = false)
                    // Internal transition (new Data, same State Class)
                    // We use the result directly
                    result
                } else {
                    // Hash matches, do nothing
                    Transition(state)
                }
            }

            is ScreenInfo.PickupDetails -> request(
                pickupStateFactory.createEntry(state, input, isRecovery = false)
            )

            is ScreenInfo.WaitingForOffer -> {
                // LOGIC: Decline or Timeout
                val declineEvent = ReducerUtils.createEvent(
                    state.dashId,
                    AppEventType.OFFER_DECLINED,
                    "Returned to search"
                )

                request(
                    awaitingStateFactory.createEntry(state, input, isRecovery = false),
                    extraEffects = listOf(AppEffect.LogEvent(declineEvent))
                )
            }

            is ScreenInfo.DashPaused -> request(
                dashPausedStateFactory.createEntry(state, input, isRecovery = false)
            )

            else -> null
        }
    }
}