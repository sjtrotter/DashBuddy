package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.reducers.MainReducer.Transition

object InitializingReducer {
    fun reduce(state: AppStateV2.Initializing, input: ScreenInfo): Transition {
        return if (input is ScreenInfo.IdleMap) {
            Transition(
                AppStateV2.IdleOffline(
                    lastKnownZone = input.zoneName,
                    dashType = input.dashType
                )
            )
        } else {
            Transition(state)
        }
    }
}