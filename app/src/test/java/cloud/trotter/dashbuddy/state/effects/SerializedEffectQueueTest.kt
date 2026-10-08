package cloud.trotter.dashbuddy.state.effects

import android.util.Log
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.test.util.RecordingTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import timber.log.Timber

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

    @Test
    fun `a wedged effect warns once then resumes and stops the watchdog`() = runTest {
        val log = mutableListOf<String>()
        val insert = CompletableDeferred<Unit>()
        val (queue, scope) = queue(log, insert)
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            queue.enqueue(AppEffect.LogEvent(mock()), recovering = false, correlationVersion = 1L)
            queue.afterProcessed { log += "snapshot" }
            runCurrent()

            advanceTimeBy(61_000)
            assertEquals(
                listOf(RecordingTree.Record(Log.WARN, "Effects", "Effect worker stalled ~60s: 2 pending, in flight: LogEvent")),
                tree.records,
            )
            advanceTimeBy(300_000)
            assertEquals("one warning throughout the stall", 1, tree.records.size)
            assertEquals("the watchdog never cancels the effect", listOf("start LogEvent"), log)

            insert.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("start LogEvent", "done LogEvent", "snapshot"), log)
            assertEquals(
                RecordingTree.Record(Log.INFO, "Effects", "Effect worker resumed after ~360s (2 drained)"),
                tree.records.last(),
            )
            assertEquals(2, tree.records.size)
            assertEquals("only the idle drain worker remains", 1, scope.coroutineContext[Job]!!.children.count())
        } finally {
            scope.cancel()
            Timber.uproot(tree)
        }
    }

    @Test
    fun `a healthy queue drains quietly and leaves no watchdog`() = runTest {
        val log = mutableListOf<String>()
        val (queue, scope) = queue(log, null)
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            queue.enqueue(AppEffect.LogEvent(mock()), recovering = false, correlationVersion = 1L)
            queue.afterProcessed { log += "snapshot" }
            val waiter = async { queue.awaitProcessed() }
            advanceUntilIdle()
            waiter.await()
            assertEquals(listOf("start LogEvent", "done LogEvent", "snapshot"), log)
            assertTrue(tree.records.isEmpty())
            assertEquals(1, scope.coroutineContext[Job]!!.children.count())

            queue.close()
            queue.enqueue(AppEffect.LogEvent(mock()), recovering = false, correlationVersion = 2L)
            queue.afterProcessed { log += "closed barrier" }
            advanceUntilIdle()
            assertEquals(listOf("start LogEvent", "done LogEvent", "snapshot"), log)
            assertTrue(tree.records.isEmpty())
            assertEquals(0, scope.coroutineContext[Job]!!.children.count())
        } finally {
            scope.cancel()
            Timber.uproot(tree)
        }
    }

    @Test
    fun `progress resets stalled ticks while the queue stays busy`() = runTest {
        val (queue, scope) = queue(mutableListOf(), null)
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            val gates = List(3) { CompletableDeferred<Unit>() }
            gates.forEach { gate -> queue.afterProcessed { gate.await() } }
            runCurrent()
            gates.forEach { gate ->
                advanceTimeBy(40_000)
                runCurrent()
                assertTrue(tree.records.isEmpty())
                gate.complete(Unit)
                runCurrent()
            }
            advanceUntilIdle()
            assertTrue(tree.records.isEmpty())
            assertEquals(1, scope.coroutineContext[Job]!!.children.count())
        } finally {
            scope.cancel()
            Timber.uproot(tree)
        }
    }

    @Test
    fun `a later stalled barrier starts a new watchdog and warning episode`() = runTest {
        val (queue, scope) = queue(mutableListOf(), null)
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            repeat(2) { episode ->
                val held = CompletableDeferred<Unit>()
                queue.afterProcessed { held.await() }
                runCurrent()
                assertEquals(2, scope.coroutineContext[Job]!!.children.count())
                advanceTimeBy(61_000)
                val warnings = tree.records.filter { it.priority == Log.WARN }
                assertEquals(episode + 1, warnings.size)
                assertEquals("Effects", warnings.last().tag)
                assertEquals("Effect worker stalled ~60s: 1 pending, in flight: barrier", warnings.last().message)

                held.complete(Unit)
                advanceUntilIdle()
                assertEquals(episode + 1, tree.records.count { it.priority == Log.INFO && "resumed" in it.message })
                assertEquals(1, scope.coroutineContext[Job]!!.children.count())
            }
        } finally {
            scope.cancel()
            Timber.uproot(tree)
        }
    }
}
