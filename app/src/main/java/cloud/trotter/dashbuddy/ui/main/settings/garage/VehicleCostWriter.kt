package cloud.trotter.dashbuddy.ui.main.settings.garage

import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import timber.log.Timber

/** One ordered write path for a details page. A failed head blocks every later write. */
class VehicleCostWriter(scope: CoroutineScope) {
    private val writes = Channel<suspend () -> Boolean>(Channel.UNLIMITED)
    private val discards = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
    private val retries = Channel<Unit>(Channel.CONFLATED)
    private val closed = CompletableDeferred<Unit>()
    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending.asStateFlow()
    private val _failure = MutableStateFlow(GarageWriteFailure.NONE)
    val failure: StateFlow<GarageWriteFailure> = _failure.asStateFlow()

    init {
        // The consumer outlives cancellation only to finish accepted commits. Owner completion
        // closes its input and releases a failed head; it never waits forever for a retry.
        scope.coroutineContext[Job]?.invokeOnCompletion { writes.close(); closed.complete(Unit) }
        CoroutineScope(scope.coroutineContext + NonCancellable).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                queue@ for (action in writes) {
                    while (!runWrite(action)) {
                        var discarded: CompletableDeferred<Unit>? = null
                        val command = select {
                            retries.onReceive { 1 }
                            closed.onAwait { 0 }
                            discards.onReceive { discarded = it; -1 }
                        }
                        if (command == 0) break@queue
                        if (command == -1) {
                            while (writes.tryReceive().isSuccess) _pending.update { it - 1 }
                            _pending.update { it - 1 }
                            _failure.value = GarageWriteFailure.NONE
                            discarded?.complete(Unit)
                            continue@queue
                        }
                    }
                    _pending.update { it - 1 }
                }
            } finally {
                writes.close()
                _pending.value = 0
            }
        }
    }

    fun write(action: suspend () -> Boolean) {
        _pending.update { it + 1 }
        if (!writes.trySend(action).isSuccess) _pending.update { it - 1 }
    }

    private suspend fun runWrite(action: suspend () -> Boolean): Boolean = withContext(NonCancellable) {
        try {
            if (action()) {
                _failure.value = GarageWriteFailure.NONE
                true
            } else {
                _failure.value = GarageWriteFailure.GONE
                false
            }
        } catch (error: Exception) {
            _failure.value = if (error is VehiclesUnreadableException) GarageWriteFailure.UNREADABLE else GarageWriteFailure.RETRY
            Timber.tag("Garage").w("%s", error.javaClass.simpleName)
            false
        }
    }

    /** Returns on a failed head too, so leaving can offer retry or discard without hanging. */
    suspend fun awaitIdle() {
        combine(pending, failure) { count, error -> count == 0 || error != GarageWriteFailure.NONE }.first { it }
    }

    suspend fun discard() {
        if (_failure.value == GarageWriteFailure.NONE) return
        val completed = CompletableDeferred<Unit>()
        discards.send(completed)
        completed.await()
    }

    fun retry() {
        if (_failure.value == GarageWriteFailure.NONE) return
        _failure.value = GarageWriteFailure.NONE
        retries.trySend(Unit)
    }
}
