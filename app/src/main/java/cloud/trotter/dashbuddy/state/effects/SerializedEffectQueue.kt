package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.core.state.AppEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * The [SideEffectEngine]'s serialized execution queue (#351), split out of the engine (#1271
 * scenario 4, Development Principle 3): one worker drains an UNLIMITED channel in enqueue order,
 * supervised so it can never die silently (#909), and runs BARRIERS in that same order — an action
 * that must not happen before the effects enqueued ahead of it have executed. `StateManagerV2`
 * writes every state snapshot through a barrier, so a snapshot never lands ahead of the `LogEvent`s
 * of the steps it covers (see `EffectExecutor.afterProcessed`).
 *
 * [execute] is the engine's per-effect executor; the worker runs on [scope].
 */
internal class SerializedEffectQueue(
    scope: CoroutineScope,
    private val execute: suspend (effect: AppEffect, recovering: Boolean, correlationVersion: Long) -> Unit,
) {
    private sealed interface Item {
        data class Effect(val effect: AppEffect, val recovering: Boolean, val correlationVersion: Long) : Item
        class Barrier(val action: suspend () -> Unit) : Item
    }

    private val channel = Channel<Item>(Channel.UNLIMITED)

    /** [awaitProcessed] callers still waiting — released by [close] so none hangs on a dead queue. */
    private val waiters = ConcurrentHashMap.newKeySet<CompletableDeferred<Unit>>()

    init {
        scope.launch { superviseDrainWorker() }
    }

    fun enqueue(effect: AppEffect, recovering: Boolean, correlationVersion: Long) {
        channel.trySend(Item.Effect(effect, recovering, correlationVersion))
    }

    /** Run [action] after every item enqueued before it. On a closed queue it never runs. */
    fun afterProcessed(action: suspend () -> Unit) {
        channel.trySend(Item.Barrier(action))
    }

    /**
     * Suspend until every item enqueued before this call has run. Throws [CancellationException]
     * instead of hanging when the queue is (or gets) [close]d first.
     */
    suspend fun awaitProcessed() {
        val done = CompletableDeferred<Unit>()
        waiters += done
        try {
            if (channel.trySend(Item.Barrier { done.complete(Unit) }).isFailure) {
                throw CancellationException("effect queue closed")
            }
            done.await()
        } finally {
            waiters -= done
        }
    }

    /** Stop accepting items and release every [awaitProcessed] waiter; the caller cancels the worker's scope. */
    fun close() {
        channel.close()
        waiters.forEach { it.cancel(CancellationException("effect queue closed")) }
    }

    /**
     * Supervisor around [drainQueue] (#909, the #430 pipeline-supervision precedent).
     *
     * The engine is a **data-integrity boundary**: `AppEffect.LogEvent` is the only writer of
     * `app_events`, which is the source of truth the whole analytics read-model is projected from.
     * A dead worker inside a live process is therefore the worst failure this codebase has — every
     * effect keeps `trySend`-ing into an UNLIMITED channel that nobody reads, so the app looks
     * healthy while an evening's earnings evaporate ($82.10 of $89.54 on 2026-07-28).
     *
     * [drainQueue] already isolates per item, so the only way out of it is the loop machinery
     * itself failing — by construction unreachable through the public API today. That is exactly
     * why it is supervised rather than trusted: the #909 class was also "unreachable" right up
     * until a one-character regex made it reachable. A restart is logged at ERROR (lost/damaged
     * subsystem, Principle 7) and backed off; queued effects survive the gap in the UNLIMITED
     * channel, so a restart costs latency, never data.
     */
    private suspend fun superviseDrainWorker() {
        var restarts = 0
        while (currentCoroutineContext().isActive) {
            try {
                drainQueue()
                return // channel closed — the only normal way out
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                restarts++
                Timber.tag("Effects").e(
                    t,
                    "Effect drain worker died unexpectedly — restarting (#%d). Effects queued " +
                        "meanwhile are buffered, not lost (#909)",
                    restarts,
                )
                delay(minOf(WORKER_RESTART_BACKOFF_MS * restarts, WORKER_RESTART_BACKOFF_MAX_MS))
            }
        }
    }

    /**
     * The serialized drain. **Catches [Throwable], not [Exception] (#909)** — an `Error` is exactly
     * what killed this worker in the field: a `PatternSyntaxException` from an ICU-invalid `Regex`
     * in a `val` initializer surfaces as `ExceptionInInitializerError`, and a class-init /
     * linkage / verify error from *any* future effect handler would do the same.
     *
     * **Which fatals are rethrown: only [CancellationException].** The usual Kotlin guidance —
     * rethrow the fatal subset ([VirtualMachineError] & friends) — assumes rethrowing preserves
     * some useful failure signal. Here it does the opposite: the scope is a `SupervisorJob` with
     * the engine's exception handler, so a rethrow does NOT crash the process and does NOT surface
     * anything to the user; it only kills the one consumer of the effect queue and converts a
     * bounded, loud, per-effect failure into unbounded silent data loss. An `OutOfMemoryError`
     * raised while executing one effect is also not proof the VM is doomed — and if it is, the
     * process dies on its own, having at least logged. Isolating everything is strictly the safer
     * trade for THIS component; nothing here is swallowed silently (every catch logs at ERROR,
     * which is the always-exported tier).
     */
    private suspend fun drainQueue() {
        for (item in channel) {
            try {
                when (item) {
                    is Item.Effect -> execute(item.effect, item.recovering, item.correlationVersion)
                    is Item.Barrier -> item.action()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.tag("Effects").e(
                    t,
                    "Effect failed — isolated, the engine is still draining: %s",
                    if (item is Item.Effect) item.effect::class.simpleName else "barrier",
                )
            }
        }
    }

    private companion object {
        /** Linear backoff step before the drain worker is restarted (#909). */
        const val WORKER_RESTART_BACKOFF_MS = 250L

        /** Ceiling on that backoff — a wedged worker must never stop retrying (#909). */
        const val WORKER_RESTART_BACKOFF_MAX_MS = 5_000L
    }
}
