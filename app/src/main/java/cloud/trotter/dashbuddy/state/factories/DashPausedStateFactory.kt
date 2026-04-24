package cloud.trotter.dashbuddy.state.factories

import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.state.TimeoutType
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.model.Transition
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DashPausedStateFactory @Inject constructor() {

    fun createEntry(
        oldState: AppStateV2,
        input: ScreenInfo.DashPaused,
        isRecovery: Boolean
    ): Transition {

        val durationMs = input.remaining.millis + 1000 // +1s buffer

        val newState = AppStateV2.DashPaused(
            dashId = oldState.dashId,
            durationMs = durationMs
        )

        Timber.i("⏸️ DASH PAUSED: '${input.remaining.text}' remaining (safety timer: ${durationMs}ms)")

        val effects = mutableListOf<AppEffect>()

        if (!isRecovery) {
            // 1. Log it
            effects.add(
                AppEffect.LogEvent(
                    ReducerUtils.createEvent(
                        oldState.dashId,
                        AppEventType.DASH_PAUSED,
                        "Paused. Time left: ${input.remaining.text}"
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