package cloud.trotter.dashbuddy.ui.main.settings.garage

import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleCostWriterTest {
    @Test fun `two writes run in order and idle waits for the last write`() = runTest {
        val writer = VehicleCostWriter(backgroundScope)
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>()
        writer.write { calls += 1; release.await(); true }
        writer.write { calls += 2; true }
        val idle = backgroundScope.async { writer.awaitIdle() }
        runCurrent()
        assertEquals(listOf(1), calls)
        assertEquals(2, writer.pending.value)
        assertFalse(idle.isCompleted)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 2), calls)
        assertTrue(idle.isCompleted)
        assertEquals(0, writer.pending.value)
    }

    @Test fun `failed head holds the next write until retry`() = runTest {
        val writer = VehicleCostWriter(backgroundScope)
        val calls = mutableListOf<Int>()
        var failing = true
        writer.write { calls += 1; if (failing) error("private name"); true }
        writer.write { calls += 2; true }
        runCurrent()
        assertEquals(GarageWriteFailure.RETRY, writer.failure.value)
        assertEquals(listOf(1), calls)
        assertEquals(2, writer.pending.value)
        writer.awaitIdle() // A failure releases the leave waiter to show its dialog.
        failing = false
        writer.retry()
        val idle = backgroundScope.async { writer.awaitIdle() }
        runCurrent()
        assertEquals(listOf(1, 1, 2), calls)
        assertEquals(GarageWriteFailure.NONE, writer.failure.value)
        assertEquals(0, writer.pending.value)
        assertTrue(idle.isCompleted)
    }

    @Test fun `refused profile update reports gone`() = runTest {
        val writer = VehicleCostWriter(backgroundScope)
        writer.write { false }
        runCurrent()
        assertEquals(GarageWriteFailure.GONE, writer.failure.value)
    }

    @Test fun `unreadable failure and warn logs carry class only`() = runTest {
        val warnings = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == 5) { assertNull(t); warnings += message }
            }
        }
        Timber.plant(tree)
        try {
            val writer = VehicleCostWriter(backgroundScope)
            writer.write { throw VehiclesUnreadableException() }
            runCurrent()
            assertEquals(GarageWriteFailure.UNREADABLE, writer.failure.value)
            assertEquals(listOf("VehiclesUnreadableException"), warnings)
        } finally { Timber.uproot(tree) }
    }

    @Test fun `accepted writes survive owner cancellation including buffered writes`() = runTest {
        val owner = Job()
        val writer = VehicleCostWriter(CoroutineScope(StandardTestDispatcher(testScheduler) + owner))
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>()
        writer.write { release.await(); calls += 1; true }
        writer.write { calls += 2; true }
        runCurrent()
        owner.cancel()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 2), calls)
        assertEquals(0, writer.pending.value)
    }

    @Test fun `closing a failed page discards blocked successors`() = runTest {
        val owner = Job()
        val writer = VehicleCostWriter(CoroutineScope(StandardTestDispatcher(testScheduler) + owner))
        writer.write { error("failed") }
        writer.write { fail("Blocked write must be discarded"); true }
        runCurrent()
        owner.cancel()
        runCurrent()
        assertEquals(0, writer.pending.value)
    }

    @Test fun `discard drops failed head and successors and page can accept fresh writes`() = runTest {
        val writer = VehicleCostWriter(backgroundScope)
        writer.write { error("failed") }
        writer.write { fail("Discarded successor must not run"); true }
        runCurrent()
        val discard = backgroundScope.async { writer.discard() }
        runCurrent()
        assertTrue(discard.isCompleted)
        assertEquals(0, writer.pending.value)
        assertEquals(GarageWriteFailure.NONE, writer.failure.value)
        var saved = false
        writer.write { saved = true; true }
        runCurrent()
        assertTrue(saved)
    }
    @Test fun `each accepted submission acknowledges equal-value success and reports failure`() = runTest {
        val writer = VehicleCostWriter(backgroundScope)
        val equal = writer.write { true }
        runCurrent()
        assertEquals(WriteResult.SAVED, equal.await())
        val failed = writer.write { error("failure") }
        val blocked = writer.write { true }
        runCurrent()
        assertEquals(WriteResult.FAILED, failed.await())
        assertEquals(WriteResult.FAILED, blocked.await())
        assertEquals(GarageWriteFailure.RETRY, writer.awaitIdle())
    }

    @Test fun `a submission after close is explicitly rejected`() = runTest {
        val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val writer = VehicleCostWriter(owner)
        owner.cancel()
        runCurrent()
        val rejected = writer.write { fail("Closed writer must not execute"); true }
        assertEquals(WriteResult.REJECTED, rejected.await())
        assertEquals(0, writer.pending.value)
    }

}
