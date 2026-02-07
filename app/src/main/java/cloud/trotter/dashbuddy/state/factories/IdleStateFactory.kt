package cloud.trotter.dashbuddy.state.factories

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.model.Transition
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdleStateFactory @Inject constructor() {

    fun createEntry(
        oldState: AppStateV2,
        input: ScreenInfo.IdleMap,
        isRecovery: Boolean
    ): Transition {
        val newState = AppStateV2.IdleOffline(
            lastKnownZone = input.zoneName,
            dashType = input.dashType
        )

        val effects = mutableListOf<AppEffect>()

        // LOGIC: If we were previously in a Dash, this is a STOP event.
        if (oldState.dashId != null) {
            val stopEvent = ReducerUtils.createEvent(
                dashId = oldState.dashId!!,
                type = AppEventType.DASH_STOP,
                payload = "Return to Map"
            )

            effects.add(AppEffect.LogEvent(stopEvent))
            effects.add(AppEffect.StopOdometer)
            effects.add(AppEffect.EndDash)
        }

        return Transition(newState, effects)
    }
}