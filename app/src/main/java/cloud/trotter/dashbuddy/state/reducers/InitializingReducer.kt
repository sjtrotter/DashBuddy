package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.factories.AwaitingStateFactory
import cloud.trotter.dashbuddy.state.factories.IdleStateFactory
import cloud.trotter.dashbuddy.state.model.Transition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InitializingReducer @Inject constructor(
    private val idleStateFactory: IdleStateFactory,
    private val awaitingStateFactory: AwaitingStateFactory,
) {
    fun reduce(state: AppStateV2.Initializing, input: ScreenInfo): Transition? {
        fun request(result: Transition) = result

        return when (input) {
            is ScreenInfo.IdleMap -> request(
                idleStateFactory.createEntry(state, input, isRecovery = false)
            )

            is ScreenInfo.WaitingForOffer -> request(
                awaitingStateFactory.createEntry(state, input, isRecovery = false)
            )

            else -> null
        }
    }
}