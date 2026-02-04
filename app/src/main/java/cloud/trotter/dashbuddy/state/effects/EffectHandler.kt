package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.event.StateEvent
import kotlinx.coroutines.CoroutineScope

interface EffectHandler {
    /**
     * Handles side effects.
     * @param dispatch A callback to send new events back to the StateManager.
     */
    fun handle(
        effect: AppEffect,
        scope: CoroutineScope,
        dispatch: (StateEvent) -> Unit
    )
}