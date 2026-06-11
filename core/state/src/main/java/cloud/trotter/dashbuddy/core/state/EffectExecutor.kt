package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.state.StateEvent
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
     * Enqueue an [AppEffect] for execution.
     *
     * ORDERING CONTRACT (#351): effects execute strictly in the order they are
     * processed — within a transition's effect list and across transitions (one
     * serialized worker). A keyed effect's durable work completes before its
     * idempotency record is written ("execute, then mark"), so crash recovery can
     * neither skip an unfinished effect nor double-run a finished one beyond that
     * single seam. Long-running waits (timers, delayed notifications) are detached
     * internally and never block the queue — for those, ordering covers the
     * *arming*, not the eventual firing.
     *
     * @param recovering When true (crash-recovery replay), external effects are
     *   suppressed and keyed effects are checked for idempotency.
     * @param correlationVersion The emitting transition's correlation version —
     *   stamped on idempotency records for replay forensics.
     */
    fun process(effect: AppEffect, recovering: Boolean = false, correlationVersion: Long = 0L)
}
