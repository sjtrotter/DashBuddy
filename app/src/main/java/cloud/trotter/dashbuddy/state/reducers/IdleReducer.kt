package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.factories.AwaitingStateFactory
import cloud.trotter.dashbuddy.state.model.Transition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdleReducer @Inject constructor(
    private val awaitingStateFactory: AwaitingStateFactory
) {

    fun reduce(
        state: AppStateV2.IdleOffline,
        input: ScreenInfo
    ): Transition? {
        return when (input) {
            is ScreenInfo.IdleMap -> {
                // Internal Update - No transition needed
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

            else -> null
        }
    }
}