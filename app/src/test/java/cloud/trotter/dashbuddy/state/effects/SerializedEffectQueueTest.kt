package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.core.state.AppEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * #1271 scenario 4 — the engine's [SerializedEffectQueue]: a barrier runs only after every effect
 * enqueued ahead of it has FINISHED executing — including one whose durable write is suspended —
 * and an [SerializedEffectQueue.awaitProcessed] waiter is released, never stranded, when the queue
 * closes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SerializedEffectQueueTest {

    private fun TestScope.queue(log: MutableList<String>, insertHeld: CompletableDeferred<Unit>?): Pair<SerializedEffectQueue, CoroutineScope> {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val queue = SerializedEffectQueue(scope) { effect, _, _ ->
            log += "start ${effect::class.simpleName}"
            if (effect is AppEffect.LogEvent) insertHeld?.await() // the event insert, in flight
            log += "done ${effect::class.simpleName}"
        }
        return queue to scope
    }

    @Test
    fun `a barrier waits for a suspended event write ahead of it`() = runTest {
        val log = mutableListOf<String>()
        val insert = CompletableDeferred<Unit>()
        val (queue, scope) = queue(log, insert)
        queue.enqueue(AppEffect.LogEvent(mock()), recovering = false, correlationVersion = 1L)
        queue.afterProcessed { log += "snapshot" }
        runCurrent()
        assertEquals("the write is in flight and the snapshot has not run", listOf("start LogEvent"), log)

        insert.complete(Unit)
        runCurrent()
        assertEquals(listOf("start LogEvent", "done LogEvent", "snapshot"), log)
        scope.cancel()
    }

    @Test
    fun `awaitProcessed returns once the effects ahead of it ran`() = runTest {
        val log = mutableListOf<String>()
        val insert = CompletableDeferred<Unit>()
        val (queue, scope) = queue(log, insert)
        queue.enqueue(AppEffect.LogEvent(mock()), recovering = true, correlationVersion = 1L)
        val waiter = async { queue.awaitProcessed() }
        runCurrent()
        assertTrue("held behind the in-flight write", waiter.isActive)
        insert.complete(Unit)
        runCurrent()
        assertTrue(waiter.isCompleted && !waiter.isCancelled)
        scope.cancel()
    }

    @Test
    fun `closing the queue releases a waiter instead of stranding it`() = runTest {
        val (queue, scope) = queue(mutableListOf(), CompletableDeferred())
        queue.enqueue(AppEffect.LogEvent(mock()), recovering = true, correlationVersion = 1L)
        val waiter = async { runCatching { queue.awaitProcessed() } }
        runCurrent()
        queue.close()
        runCurrent()
        assertTrue("released with a cancellation", waiter.await().exceptionOrNull() is CancellationException)

        // …and a wait started AFTER the close fails at once.
        assertTrue(runCatching { queue.awaitProcessed() }.exceptionOrNull() is CancellationException)
        scope.cancel()
    }
}
