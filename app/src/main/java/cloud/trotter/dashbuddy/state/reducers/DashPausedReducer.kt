package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.Screen
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.factories.AwaitingStateFactory
import cloud.trotter.dashbuddy.state.factories.IdleStateFactory
import cloud.trotter.dashbuddy.state.factories.PostDeliveryStateFactory
import cloud.trotter.dashbuddy.state.factories.SummaryStateFactory
import cloud.trotter.dashbuddy.state.model.TimeoutType
import cloud.trotter.dashbuddy.state.model.Transition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DashPausedReducer @Inject constructor(
    private val idleStateFactory: IdleStateFactory,
    private val awaitingStateFactory: AwaitingStateFactory,
    private val summaryStateFactory: SummaryStateFactory,
    private val postDeliveryStateFactory: PostDeliveryStateFactory,
) {

    fun onTimeout(state: AppStateV2, type: TimeoutType): Transition {
        return when (type) {
            TimeoutType.DASH_PAUSED_SAFETY -> {
                // Force transition to Idle (Dash Ended by Timeout)
                val dummyInput = ScreenInfo.IdleMap(Screen.MAIN_MAP_IDLE, "Unknown", null)
                val result = idleStateFactory.createEntry(state, dummyInput, isRecovery = false)

                Transition(
                    newState = result.newState,
                    effects = result.effects + AppEffect.UpdateBubble("Dash Ended (Timeout)")
                )
            }

            else -> Transition(state)
        }
    }

    fun reduce(state: AppStateV2.DashPaused, input: ScreenInfo): Transition? {

        // Helper: Must cancel the timer on exit
        fun request(factoryResult: Transition): Transition {
            return factoryResult.copy(
                effects = factoryResult.effects + AppEffect.CancelTimeout(TimeoutType.DASH_PAUSED_SAFETY)
            )
        }

        return when (input) {
            is ScreenInfo.DashPaused -> {
                val newEnd = System.currentTimeMillis() + input.remainingMillis
                Transition(state.copy(durationMs = newEnd))
            }

            is ScreenInfo.WaitingForOffer -> request(
                awaitingStateFactory.createEntry(state, input, isRecovery = false)
            )

            is ScreenInfo.DashSummary -> request(
                summaryStateFactory.createEntry(state, input, isRecovery = false)
            )

            is ScreenInfo.IdleMap -> request(
                idleStateFactory.createEntry(state, input, isRecovery = false)
            )

            is ScreenInfo.DeliveryCompleted -> request(
                postDeliveryStateFactory.createEntry(state, input, isRecovery = false)
            )

            else -> null
        }
    }
}