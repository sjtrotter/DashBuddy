package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.factories.IdleStateFactory
import cloud.trotter.dashbuddy.state.model.Transition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SummaryReducer @Inject constructor(
    private val idleStateFactory: IdleStateFactory,
) {
    fun reduce(state: AppStateV2.PostDash, input: ScreenInfo): Transition? {
        return when (input) {
            is ScreenInfo.IdleMap -> {
                // Just return the factory result directly
                idleStateFactory.createEntry(state, input, isRecovery = false)
            }

            is ScreenInfo.DashSummary -> Transition(state)
            else -> null
        }
    }
}