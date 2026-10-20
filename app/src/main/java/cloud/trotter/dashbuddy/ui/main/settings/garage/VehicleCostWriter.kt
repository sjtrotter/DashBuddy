package cloud.trotter.dashbuddy.ui.main.settings.garage

import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import timber.log.Timber

enum class WriteResult { SAVED, FAILED, REJECTED, DISCARDED, CHANGED }

/** The first attempt releases leave waiters on failure; completion follows the retained save. */
// Every member forwards to a real CompletableDeferred; no coroutines internals are reimplemented.
@OptIn(InternalForInheritanceCoroutinesApi::class)
class WriteSubmission internal constructor(
    private val first: CompletableDeferred<WriteResult> = CompletableDeferred(),
) : Deferred<WriteResult> by first {
    internal var attempt = first
    private val finalResult = CompletableDeferred<WriteResult>()
    val completion: Deferred<WriteResult> = finalResult
    internal fun complete(result: WriteResult) {
        attempt.complete(result)
        finalResult.complete(result)
    }
}

/** Ordered settings writes. A failed head blocks successors until retry or discard. */
class VehicleCostWriter(scope: CoroutineScope) {
    private class Write(val action: suspend () -> WriteResult) {
        val result = WriteSubmission()
    }
    private val writes = Channel<Write>(Channel.UNLIMITED)
    private val outstanding = linkedSetOf<Write>()
    private val discards = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
    private val retries = Channel<Unit>(Channel.CONFLATED)
    private val closed = CompletableDeferred<Unit>()
    private val _pending = MutableStateFlow(0)
    val pending = _pending.asStateFlow()
    private val _failure = MutableStateFlow(GarageWriteFailure.NONE)
    val failure = _failure.asStateFlow()

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { writes.close(); closed.complete(Unit) }
        CoroutineScope(scope.coroutineContext + NonCancellable).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                queue@ for (write in writes) {
                    var outcome = runWrite(write.action)
                    while (outcome == WriteResult.FAILED) {
                        // Release every waiter on failure, including successors blocked by this head.
                        outstanding.toList().forEach { it.result.attempt.complete(WriteResult.FAILED) }
                        var discarded: CompletableDeferred<Unit>? = null
                        val command = select {
                            retries.onReceive { 1 }
                            closed.onAwait { 0 }
                            discards.onReceive { discarded = it; -1 }
                        }
                        if (command == 0) break@queue
                        if (command == -1) {
                            while (writes.tryReceive().isSuccess) Unit
                            outstanding.toList().forEach { it.result.complete(WriteResult.DISCARDED) }
                            outstanding.clear()
                            _pending.value = 0
                            _failure.value = GarageWriteFailure.NONE
                            discarded?.complete(Unit)
                            continue@queue
                        }
                        outcome = runWrite(write.action)
                    }
                    write.result.complete(outcome)
                    outstanding.remove(write)
                    _pending.value = outstanding.size
                }
            } finally {
                writes.close()
                outstanding.toList().forEach { it.result.complete(WriteResult.REJECTED) }
                outstanding.clear()
                _pending.value = 0
            }
        }
    }

    /** Acceptance is synchronous; accepted actions outlive the owner's scope. */
    fun write(action: suspend () -> Boolean): Deferred<WriteResult> =
        writeResult { if (action()) WriteResult.SAVED else WriteResult.FAILED }

    fun writeResult(action: suspend () -> WriteResult): Deferred<WriteResult> {
        val write = Write(action)
        if (closed.isCompleted) {
            write.result.complete(WriteResult.REJECTED)
            return write.result
        }
        outstanding.add(write)
        _pending.value = outstanding.size
        if (!writes.trySend(write).isSuccess) {
            outstanding.remove(write)
            _pending.value = outstanding.size
            write.result.complete(WriteResult.REJECTED)
        } else if (_failure.value != GarageWriteFailure.NONE) {
            write.result.attempt.complete(WriteResult.FAILED)
        }
        return write.result
    }

    private suspend fun runWrite(action: suspend () -> WriteResult): WriteResult = withContext(NonCancellable) {
        try {
            val result = action()
            _failure.value = if (result == WriteResult.FAILED) GarageWriteFailure.GONE else GarageWriteFailure.NONE
            result
        } catch (error: Exception) {
            _failure.value = if (error is VehiclesUnreadableException) GarageWriteFailure.UNREADABLE else GarageWriteFailure.RETRY
            Timber.tag("Garage").w("%s", error.javaClass.simpleName)
            WriteResult.FAILED
        }
    }

    /** A failed head releases the host to show Try again / Discard. */
    suspend fun awaitIdle(): GarageWriteFailure {
        combine(pending, failure) { count, error -> count == 0 || error != GarageWriteFailure.NONE }.first { it }
        return failure.value
    }

    suspend fun discard() {
        if (_failure.value == GarageWriteFailure.NONE) { awaitIdle(); if (_failure.value == GarageWriteFailure.NONE) return }
        if (closed.isCompleted) return
        val completed = CompletableDeferred<Unit>()
        discards.send(completed)
        completed.await()
    }

    fun retry() {
        if (_failure.value == GarageWriteFailure.NONE) return
        outstanding.forEach { it.result.attempt = CompletableDeferred() }
        _failure.value = GarageWriteFailure.NONE
        retries.trySend(Unit)
    }
}
