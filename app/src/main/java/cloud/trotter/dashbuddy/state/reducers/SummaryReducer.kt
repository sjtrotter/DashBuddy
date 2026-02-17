package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.event.ScreenUpdateEvent
import cloud.trotter.dashbuddy.state.event.StateEvent
import cloud.trotter.dashbuddy.state.factories.IdleStateFactory
import cloud.trotter.dashbuddy.state.model.Transition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SummaryReducer @Inject constructor(
    private val idleStateFactory: IdleStateFactory,
) {

    // --- NEW CONTRACT: Handle ANY StateEvent ---
    fun reduce(state: AppStateV2.PostDash, event: StateEvent): Transition? {
        return when (event) {
            is ScreenUpdateEvent -> {
                val input = event.screenInfo ?: return null
                handleScreenUpdate(state, input)
            }

            else -> null
        }
    }

    private fun handleScreenUpdate(state: AppStateV2.PostDash, input: ScreenInfo): Transition? {
        return when (input) {
            is ScreenInfo.IdleMap -> {
                // Return the factory result directly
                idleStateFactory.createEntry(state, input, isRecovery = false)
            }

            is ScreenInfo.DashSummary -> Transition(state)

            else -> null
        }
    }
}