package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.ratings.RatingsSnapshot
import cloud.trotter.dashbuddy.domain.model.state.ScreenUpdateEvent
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.factories.AwaitingStateFactory
import cloud.trotter.dashbuddy.state.model.Transition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdleReducer @Inject constructor(
    private val awaitingStateFactory: AwaitingStateFactory
) {

    // --- NEW CONTRACT: Handle ANY StateEvent ---
    fun reduce(
        state: AppStateV2.IdleOffline,
        event: StateEvent
    ): Transition? {
        return when (event) {
            is ScreenUpdateEvent -> {
                val input = event.screenInfo ?: return null
                handleScreenUpdate(state, input)
            }
            // Idle state usually doesn't handle Clicks or Timeouts directly yet
            else -> null
        }
    }

    private fun handleScreenUpdate(
        state: AppStateV2.IdleOffline,
        input: ScreenInfo
    ): Transition? {
        return when (input) {
            is ScreenInfo.IdleMap -> {
                // Internal Update - No transition needed, just update data if changed
                if (state.lastKnownZone != input.zoneName || state.dashType != input.dashType) {
                    Transition(
                        state.copy(
                            lastKnownZone = input.zoneName,
                            dashType = input.dashType
                        )
                    )
                } else {
                    Transition(state)
                }
            }

            is ScreenInfo.WaitingForOffer -> {
                // Just return the transition package from the factory
                awaitingStateFactory.createEntry(state, input, isRecovery = false)
            }

            is ScreenInfo.Ratings -> {
                val snapshot = RatingsSnapshot(
                    acceptanceRate = input.acceptanceRate,
                    completionRate = input.completionRate,
                    onTimeRate = input.onTimeRate,
                    customerRating = input.customerRating,
                    deliveriesLast30Days = input.deliveriesLast30Days,
                    lifetimeDeliveries = input.lifetimeDeliveries,
                    originalItemsFoundRate = input.originalItemsFoundRate,
                    totalItemsFoundRate = input.totalItemsFoundRate,
                    substitutionIssuesRate = input.substitutionIssuesRate,
                    itemsWithQualityIssuesRate = input.itemsWithQualityIssuesRate,
                    itemsWrongOrMissingRate = input.itemsWrongOrMissingRate,
                    lifetimeShoppingOrders = input.lifetimeShoppingOrders,
                )
                Transition(state.copy(latestRatings = snapshot))
            }

            else -> null
        }
    }
}