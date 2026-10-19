package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.OdometerReadingPayload
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerWriteFailure
import kotlinx.coroutines.flow.flowOf
import cloud.trotter.dashbuddy.core.data.vehicle.OdometerReadingRepository
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
    private val garage: GarageRepository = mock()
    private val readings: OdometerReadingRepository = mock()
    private val clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)

    @Test fun `successful append publishes the saved notice`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings, clock)
        actions.save("civic", 52_388)
        assertTrue(actions.saving.value)
        runCurrent()
        verify(readings).record("civic", 52_388)
        assertEquals(52_388, actions.notice.value!!.readingMiles)
        assertFalse(actions.saving.value)
        assertEquals(OdometerWriteFailure.NONE, actions.failed.value)
    }

    @Test fun `failed append keeps the sheet available for retry`() = runTest {
        whenever(readings.record(any(), any())).thenThrow(IllegalStateException("storage unavailable"))
        val actions = OdometerActions(backgroundScope, garage, readings, clock)
        actions.save("civic", 52_388)
        runCurrent()
        assertNull(actions.notice.value)
        assertEquals(OdometerWriteFailure.RETRY, actions.failed.value)
        assertFalse(actions.saving.value)
        actions.reopen()
        assertEquals(OdometerWriteFailure.NONE, actions.failed.value)
    }

    @Test fun `double tap appends only once`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings, clock)
        actions.save("civic", 52_388)
        actions.save("civic", 52_388)
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
        val actions = OdometerActions(scope, garage, readings, clock)
        actions.save("civic", 52_388)
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
        val actions = OdometerActions(backgroundScope, garage, readings, clock)
        actions.add("Civic"); runCurrent()
        actions.select("civic"); runCurrent()
        verify(garage).addVehicle("Civic", 100)
        verify(garage).selectVehicle("civic")
        verifyNoInteractions(readings)
    }
    @Test fun `the notice clears itself after ten seconds`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings, clock)
        actions.save("civic", 52_388)
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
        val actions = OdometerActions(backgroundScope, garage, readings, clock)
        actions.save("civic", 52_388)
        runCurrent()
        val first = actions.notice.value!!
        advanceTimeBy(5_000)
        actions.save("civic", 52_400)
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
        val actions = OdometerActions(backgroundScope, garage, readings, clock)
        actions.save("civic", 52_388)
        runCurrent()
        val first = actions.notice.value!!
        advanceTimeBy(5_000)
        actions.save("civic", 52_388)
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

    @Test fun `the reading uses the repository clock instead of the action clock`() = runTest {
        val events: AppEventRepo = mock()
        val gps: OdometerRepository = mock()
        whenever(events.getSequencedEventsByType(AppEventType.ODOMETER_READING)).thenReturn(flowOf(emptyList()))
        whenever(gps.totalMiles()).thenReturn(123.5)
        val repositoryClock = Clock.fixed(Instant.ofEpochMilli(900), ZoneOffset.UTC)
        val repository = OdometerReadingRepository(events, gps, repositoryClock)
        val actions = OdometerActions(backgroundScope, garage, repository, clock)
        actions.save("civic", 52_388)
        runCurrent()
        verify(events).appendUserEvent(AppEvent(AppEventType.ODOMETER_READING, 900, null,
            OdometerReadingPayload("civic", 52_388, 123.5)), null)
    }

    @Test fun `a slow write still shows the notice for ten seconds`() = runTest {
        // A repository answer runs on the test scheduler and suspends the append for eleven seconds.
        doSuspendableAnswer { delay(11_000); 1L }
            .whenever(readings).record(any(), any())
        val actions = OdometerActions(backgroundScope, garage, readings, clock)
        actions.save("civic", 52_388)
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
        whenever(garage.addVehicle(any(), any())).thenThrow(VehiclesUnreadableException())
        val actions = OdometerActions(backgroundScope, garage, readings, clock)
        actions.add("Civic")
        runCurrent()
        assertEquals(OdometerWriteFailure.UNREADABLE, actions.failed.value)
        assertFalse(actions.saving.value)
        assertNull(actions.notice.value)
    }

}
