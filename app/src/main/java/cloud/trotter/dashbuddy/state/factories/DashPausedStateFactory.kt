package cloud.trotter.dashbuddy.state.factories

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.model.TimeoutType
import cloud.trotter.dashbuddy.state.model.Transition
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DashPausedStateFactory @Inject constructor() {

    fun createEntry(
        oldState: AppStateV2,
        input: ScreenInfo.DashPaused,
        isRecovery: Boolean
    ): Transition {

        val durationMs = input.remainingMillis + 1000 // +1s buffer

        val newState = AppStateV2.DashPaused(
            dashId = oldState.dashId,
            durationMs = durationMs
        )

        val effects = mutableListOf<AppEffect>()

        if (!isRecovery) {
            // 1. Log it
            effects.add(
                AppEffect.LogEvent(
                    ReducerUtils.createEvent(
                        oldState.dashId,
                        AppEventType.DASH_PAUSED,
                        "Paused. Time left: ${input.rawTimeText}"
                    )
                )
            )

            // 2. Schedule Safety Timer
            effects.add(
                AppEffect.ScheduleTimeout(
                    durationMs = durationMs,
                    type = TimeoutType.DASH_PAUSED_SAFETY
                )
            )

            effects.add(AppEffect.UpdateBubble("Dash Paused!"))
        }

        return Transition(newState, effects)
    }
}