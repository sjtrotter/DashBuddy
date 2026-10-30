package cloud.trotter.log.gig.ui.bubble

import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.data.vehicle.VehiclesUnreadableException
import cloud.trotter.log.gig.core.data.event.AppEventRepo
import cloud.trotter.log.gig.core.data.location.OdometerRepository
import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.OdometerReadingPayload
import cloud.trotter.log.gig.feature.bubble.hud.odometer.OdometerWriteFailure
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import cloud.trotter.log.gig.core.data.vehicle.OdometerReadingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class OdometerActionsTest {
    // A select succeeds unless a test says otherwise (a refused select is the stale-row case).
    private val garage: GarageRepository = mock {
        on { garage } doReturn flowOf(Garage(listOf(Vehicle("civic", "Civic", 0)), "civic", true))
        on { selectVehicle(any()) } doReturn true
    }
    private val readings: OdometerReadingRepository = mock()

    @Test fun `successful append publishes the saved notice`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.save("civic", 52_388, 42L)
        assertTrue(actions.saving.value)
        runCurrent()
        verify(readings).record("civic", 52_388)
        assertEquals(52_388, actions.notice.value!!.readingMiles)
        assertFalse(actions.saving.value)
        assertEquals(OdometerWriteFailure.NONE, actions.failed.value)
    }

    @Test fun `failed append keeps the sheet available for retry`() = runTest {
        whenever(readings.record(any(), any())).thenThrow(IllegalStateException("storage unavailable"))
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.save("civic", 52_388, 42L)
        runCurrent()
        assertNull(actions.notice.value)
        assertEquals(OdometerWriteFailure.RETRY, actions.failed.value)
        assertFalse(actions.saving.value)
        actions.reopen()
        assertEquals(OdometerWriteFailure.NONE, actions.failed.value)
    }

    @Test fun `a completed save emits its captured entry id once`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings)
        val completions = mutableListOf<Long>()
        backgroundScope.launch { actions.saveCompleted.collect { completions += it; actions.saveHandled() } }
        runCurrent()
        repeat(2) { index ->
            actions.save("civic", 52_388, index.toLong())
            runCurrent()
            assertEquals((0..index).map { it.toLong() }, completions)
        }
        whenever(readings.record(any(), any())).thenThrow(IllegalStateException("storage unavailable"))
        actions.save("civic", 52_400, 42L)
        runCurrent()
        assertEquals(2, completions.size)
        assertEquals(OdometerWriteFailure.RETRY, actions.failed.value)

        val lateCompletions = mutableListOf<Long>()
        backgroundScope.launch { actions.saveCompleted.collect { lateCompletions += it } }
        runCurrent()
        assertTrue(lateCompletions.isEmpty())
    }

    @Test fun `a late collector sees one pending completion`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.save("civic", 52_388, 42L)
        runCurrent()
        val completions = mutableListOf<Long>()
        backgroundScope.launch { actions.saveCompleted.collect { completions += it } }
        runCurrent()
        assertEquals(listOf(42L), completions)
    }

    @Test fun `a handled completion is not replayed`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.save("civic", 52_388, 42L)
        runCurrent()
        val completions = mutableListOf<Long>()
        val collector = backgroundScope.launch {
            actions.saveCompleted.collect { completions += it; actions.saveHandled() }
        }
        runCurrent()
        assertEquals(listOf(42L), completions)
        collector.cancel()
        val lateCompletions = mutableListOf<Long>()
        backgroundScope.launch { actions.saveCompleted.collect { lateCompletions += it } }
        runCurrent()
        assertTrue(lateCompletions.isEmpty())
    }

    @Test fun `a refused select emits nothing`() = runTest {
        // A stale menu item: the repository refuses the id, so nothing changed and no event may fire or replay.
        whenever(garage.selectVehicle("stale")).thenReturn(false)
        val actions = OdometerActions(backgroundScope, garage, readings)
        val changes = mutableListOf<VehicleChange>()
        backgroundScope.launch { actions.vehicleChanged.collect { changes += it } }
        runCurrent()
        actions.select("stale")
        runCurrent()
        verify(garage).selectVehicle("stale")
        assertEquals(emptyList<VehicleChange>(), changes)
        assertEquals(OdometerWriteFailure.NONE, actions.failed.value)
        val late = mutableListOf<VehicleChange>()
        backgroundScope.launch { actions.vehicleChanged.collect { late += it } }
        runCurrent()
        assertEquals(emptyList<VehicleChange>(), late)
    }

    @Test fun `a selected vehicle emits SWITCHED once`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings)
        val changes = mutableListOf<VehicleChange>()
        backgroundScope.launch { actions.vehicleChanged.collect { changes += it } }
        runCurrent()
        actions.select("civic")
        runCurrent()
        verify(garage).selectVehicle("civic")
        assertEquals(listOf(VehicleChange.SWITCHED), changes)
    }

    @Test fun `a late collector sees a vehicle change until handled`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.select("civic")
        runCurrent()
        val changes = mutableListOf<VehicleChange>()
        val collector = backgroundScope.launch {
            actions.vehicleChanged.collect { changes += it; actions.vehicleChangeHandled() }
        }
        runCurrent()
        assertEquals(listOf(VehicleChange.SWITCHED), changes)
        collector.cancel()
        val lateChanges = mutableListOf<VehicleChange>()
        val lateCollector = backgroundScope.launch { actions.vehicleChanged.collect { lateChanges += it } }
        runCurrent()
        assertTrue(lateChanges.isEmpty())
        lateCollector.cancel()
    }

    @Test fun `a vehicle select keeps a save failure visible`() = runTest {
        whenever(readings.record(any(), any())).thenThrow(IllegalStateException("storage unavailable"))
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.save("civic", 52_388, 42L)
        runCurrent()
        assertEquals(OdometerWriteFailure.RETRY, actions.failed.value)
        actions.select("civic")
        runCurrent()
        assertEquals(OdometerWriteFailure.RETRY, actions.failed.value)
        doSuspendableAnswer { 1L }.whenever(readings).record(any(), any())
        actions.save("civic", 52_388, 42L)
        runCurrent()
        assertEquals(OdometerWriteFailure.NONE, actions.failed.value)
    }

    @Test fun `a successful select clears a select failure`() = runTest {
        whenever(garage.selectVehicle("civic")).thenThrow(IllegalStateException("storage unavailable"))
            .thenReturn(true)
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.select("civic")
        runCurrent()
        assertEquals(OdometerWriteFailure.RETRY, actions.failed.value)
        actions.select("civic")
        assertEquals(OdometerWriteFailure.RETRY, actions.failed.value)
        runCurrent()
        assertEquals(OdometerWriteFailure.NONE, actions.failed.value)
        assertEquals(listOf(VehicleChange.SWITCHED), actions.vehicleChanged.replayCache)
    }

    @Test fun `a refused select keeps a vehicle failure`() = runTest {
        whenever(garage.selectVehicle("civic")).thenThrow(IllegalStateException("storage unavailable"))
        whenever(garage.selectVehicle("stale")).thenReturn(false)
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.select("civic")
        runCurrent()
        assertEquals(OdometerWriteFailure.RETRY, actions.failed.value)
        actions.select("stale")
        runCurrent()
        // Nothing was written, so nothing was corrected: the failure stands.
        assertEquals(OdometerWriteFailure.RETRY, actions.failed.value)
        assertEquals(emptyList<VehicleChange>(), actions.vehicleChanged.replayCache)
    }

    @Test fun `a reading save keeps a vehicle failure visible`() = runTest {
        whenever(garage.selectVehicle("civic")).thenThrow(IllegalStateException("storage unavailable"))
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.select("civic")
        runCurrent()
        assertEquals(OdometerWriteFailure.RETRY, actions.failed.value)
        actions.save("civic", 52_388, 42L)
        runCurrent()
        assertEquals(OdometerWriteFailure.RETRY, actions.failed.value)
        assertNotNull(actions.notice.value)
        actions.reopen()
        assertEquals(OdometerWriteFailure.NONE, actions.failed.value)
    }

    @Test fun `double tap appends only once`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.save("civic", 52_388, 42L)
        actions.save("civic", 52_388, 42L)
        runCurrent()
        verify(readings, times(1)).record("civic", 52_388)
    }

    @Test fun `a cancelled scope still completes the write`() = runTest {
        val recorded = mutableListOf<Pair<String, Int>>()
        doSuspendableAnswer { call ->
            delay(1_000)
            recorded += Pair(call.getArgument<String>(0), call.getArgument<Int>(1))
            1L
        }.whenever(readings).record(any(), any())
        val scope = CoroutineScope(coroutineContext + Job())
        val actions = OdometerActions(scope, garage, readings)
        actions.save("civic", 52_388, 42L)
        runCurrent()
        assertTrue(actions.saving.value)
        assertTrue(recorded.isEmpty())
        scope.cancel()
        advanceUntilIdle()
        assertEquals(listOf(Pair("civic", 52_388)), recorded)
        assertEquals(52_388, actions.notice.value!!.readingMiles)
        assertFalse(actions.saving.value)
        assertEquals(OdometerWriteFailure.NONE, actions.failed.value)
    }

    @Test fun `vehicle actions use the garage without writing a reading`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.select("civic"); runCurrent()
        verify(garage).selectVehicle("civic")
        verifyNoInteractions(readings)
    }
    @Test fun `the notice clears itself after ten seconds`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.save("civic", 52_388, 42L)
        runCurrent()
        val notice = actions.notice.value!!
        advanceTimeBy(9_999)
        runCurrent()
        assertSame(notice, actions.notice.value)
        advanceTimeBy(1)
        runCurrent()
        assertNull(actions.notice.value)
    }

    @Test fun `a newer notice outlives the older timer`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.save("civic", 52_388, 42L)
        runCurrent()
        val first = actions.notice.value!!
        advanceTimeBy(5_000)
        actions.save("civic", 52_400, 42L)
        runCurrent()
        val second = actions.notice.value!!
        assertEquals(52_400, second.readingMiles)
        assertNotSame(first, second)
        advanceTimeBy(5_000)
        runCurrent()
        assertSame(second, actions.notice.value)
        advanceTimeBy(4_999)
        runCurrent()
        assertSame(second, actions.notice.value)
        advanceTimeBy(1)
        runCurrent()
        assertNull(actions.notice.value)
    }

    @Test fun `an identical second save gets its own ten seconds`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.save("civic", 52_388, 42L)
        runCurrent()
        val first = actions.notice.value!!
        advanceTimeBy(5_000)
        actions.save("civic", 52_388, 42L)
        runCurrent()
        val second = actions.notice.value!!
        assertEquals(first.readingMiles, second.readingMiles)
        assertTrue(second.token > first.token)
        advanceTimeBy(5_000)
        runCurrent()
        assertSame(second, actions.notice.value)
        advanceTimeBy(4_999)
        runCurrent()
        assertSame(second, actions.notice.value)
        advanceTimeBy(1)
        runCurrent()
        assertNull(actions.notice.value)
    }

    @Test fun `the reading uses the repository clock`() = runTest {
        val events: AppEventRepo = mock()
        val gps: OdometerRepository = mock()
        whenever(events.getSequencedEventsByType(AppEventType.ODOMETER_READING)).thenReturn(flowOf(emptyList()))
        whenever(gps.totalMiles()).thenReturn(123.5)
        val repositoryClock = Clock.fixed(Instant.ofEpochMilli(900), ZoneOffset.UTC)
        val repository = OdometerReadingRepository(events, gps, repositoryClock)
        val actions = OdometerActions(backgroundScope, garage, repository)
        actions.save("civic", 52_388, 42L)
        runCurrent()
        verify(events).appendUserEvent(AppEvent(AppEventType.ODOMETER_READING, 900, null,
            OdometerReadingPayload("civic", 52_388, 123.5)), null)
    }

    @Test fun `a slow write still shows the notice for ten seconds`() = runTest {
        // A repository answer runs on the test scheduler and suspends the append for eleven seconds.
        doSuspendableAnswer { delay(11_000); 1L }
            .whenever(readings).record(any(), any())
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.save("civic", 52_388, 42L)
        runCurrent()
        advanceTimeBy(11_000)
        assertTrue(actions.saving.value)
        assertNull(actions.notice.value)
        runCurrent()
        verify(readings).record("civic", 52_388)
        val notice = actions.notice.value!!
        assertSame(notice, actions.notice.value)
        advanceTimeBy(9_999)
        runCurrent()
        assertSame(notice, actions.notice.value)
        advanceTimeBy(1)
        runCurrent()
        assertNull(actions.notice.value)
    }

    @Test fun `an unreadable garage is its own failure`() = runTest {
        whenever(garage.selectVehicle(any())).thenThrow(VehiclesUnreadableException())
        val actions = OdometerActions(backgroundScope, garage, readings)
        actions.select("civic")
        runCurrent()
        assertEquals(OdometerWriteFailure.UNREADABLE, actions.failed.value)
        assertFalse(actions.saving.value)
        assertNull(actions.notice.value)
    }
}
