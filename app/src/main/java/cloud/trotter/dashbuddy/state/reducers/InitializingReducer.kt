package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.Reducer

object InitializingReducer {

    // LOGIC: We are initializing, looking for the Map.
    fun reduce(
        state: AppStateV2.Initializing,
        input: ScreenInfo
    ): Reducer.Transition? {
        return if (input is ScreenInfo.IdleMap) {
            IdleReducer.transitionTo(state, input, isRecovery = false)
        } else {
            null
        }
    }
}