package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.Reducer

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