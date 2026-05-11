package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * Abstraction over the concrete side-effect engine.
 *
 * Lives in `:core:state` so [StateManagerV2] can depend on it without
 * reaching into `:app` for the concrete [SideEffectEngine].
 * `:app` provides the implementation via Hilt.
 */
interface EffectExecutor {

    /** Events flowing back to the state machine (timeouts, evaluations). */
    val events: SharedFlow<StateEvent>

    /**
     * Execute an [AppEffect].
     *
     * @param recovering When true (crash-recovery replay), external effects are
     *   suppressed and keyed effects are checked for idempotency.
     */
    fun process(effect: AppEffect, scope: CoroutineScope, recovering: Boolean = false)
}
