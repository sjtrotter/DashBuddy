package cloud.trotter.log.gig.ui.main.settings.garage

import cloud.trotter.log.gig.core.data.vehicle.VehiclesUnreadableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
class GarageWriterTest {
    @Test fun `started write finishes after owner cancellation and releases busy`() = runTest {
        val owner = Job()
        val writer = GarageWriter(CoroutineScope(StandardTestDispatcher(testScheduler) + owner), "Garage")
        val release = CompletableDeferred<Unit>()
        var committed = false
        writer.write {
            release.await()
            committed = true
            true
        }
        runCurrent()
        owner.cancel()
        assertTrue(writer.busy.value)
        release.complete(Unit)
        runCurrent()
        assertTrue(committed)
        assertFalse(writer.busy.value)
        assertEquals(GarageWriteFailure.NONE, writer.failure.value)
    }

    @Test fun `exception mapping logs only class names and a successful retry clears failure`() = runTest {
        val warnings = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == 5 && tag == "Garage") warnings += message
            }
        }
        Timber.plant(tree)
        try {
            val writer = GarageWriter(backgroundScope, "Garage")
            writer.write { throw IllegalArgumentException("private vehicle name") }
            runCurrent()
            assertEquals(GarageWriteFailure.RETRY, writer.failure.value)
            writer.write { throw VehiclesUnreadableException() }
            runCurrent()
            assertEquals(GarageWriteFailure.UNREADABLE, writer.failure.value)
            writer.write { true }
            runCurrent()
            assertEquals(GarageWriteFailure.NONE, writer.failure.value)
            assertEquals(listOf("IllegalArgumentException", "VehiclesUnreadableException"), warnings)
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test fun `refused edit reports gone and does not complete`() = runTest {
        val writer = GarageWriter(backgroundScope, "Garage")
        writer.write(refused = GarageWriteFailure.GONE, onDone = { fail("Refused edit must stay open") }) { false }
        runCurrent()
        assertEquals(GarageWriteFailure.GONE, writer.failure.value)
        assertFalse(writer.busy.value)
    }
}
