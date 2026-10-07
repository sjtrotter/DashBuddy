package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import kotlinx.coroutines.CompletableDeferred
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

    /**
     * Run [action] on the serialized worker AFTER every effect [process]ed before this call has
     * executed, and before anything processed after it (#1271 scenario 4).
     *
     * This is how a state snapshot is kept from getting AHEAD of the effects of the steps it
     * covers. Recovery restores the latest snapshot and replays only the journal AFTER it, so a
     * snapshot of step N that lands while step N's `LogEvent`s still wait in this queue makes
     * those events unreachable: a process death in between loses them for good (a retire's
     * `DELIVERY_CONFIRMED`, a dash's `DASH_STOP`). A snapshot that lags is harmless — the replay
     * re-issues the keyed effects it covers and `effects_fired` dedupes them. So every snapshot
     * write goes through here. If the process dies first, the action dies with the queue, which
     * is the point: no snapshot, so the replay still reaches those steps.
     */
    fun afterProcessed(action: suspend () -> Unit)

    /** Suspend until every effect [process]ed before this call has executed — [afterProcessed], awaited. */
    suspend fun awaitProcessed() {
        val done = CompletableDeferred<Unit>()
        afterProcessed { done.complete(Unit) }
        done.await()
    }
}
