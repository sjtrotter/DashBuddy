package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.Reducer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InitializingReducer @Inject constructor(
    private val idleReducer: IdleReducer,
    private val awaitingReducer: AwaitingReducer,
) {

    // LOGIC: We are initializing, looking for the Map.
    fun reduce(
        state: AppStateV2.Initializing,
        input: ScreenInfo
    ): Reducer.Transition? {
        return when (input) {
            is ScreenInfo.IdleMap -> {
                idleReducer.transitionTo(state, input, isRecovery = false)
            }

            is ScreenInfo.WaitingForOffer -> {
                awaitingReducer.transitionTo(state, input, isRecovery = false)
            }

            else -> null
        }
    }
}