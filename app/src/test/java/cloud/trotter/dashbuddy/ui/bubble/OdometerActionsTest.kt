package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerNoticeModel
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
        verify(readings).record("civic", 52_388, 100)
        assertEquals(OdometerNoticeModel(52_388, 100), actions.notice.value)
        assertFalse(actions.saving.value)
        assertFalse(actions.failed.value)
    }

    @Test fun `failed append keeps the sheet available for retry`() = runTest {
        whenever(readings.record(any(), any(), any())).thenThrow(IllegalStateException("storage unavailable"))
        val actions = OdometerActions(backgroundScope, garage, readings, clock)
        actions.save("civic", 52_388)
        runCurrent()
        assertNull(actions.notice.value)
        assertTrue(actions.failed.value)
        assertFalse(actions.saving.value)
        actions.reopen()
        assertFalse(actions.failed.value)
    }

    @Test fun `double tap appends only once`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings, clock)
        actions.save("civic", 52_388)
        actions.save("civic", 52_388)
        runCurrent()
        verify(readings, times(1)).record("civic", 52_388, 100)
    }

    @Test fun `a cancelled scope still completes the write`() = runTest {
        val recorded = mutableListOf<Triple<String, Int, Long>>()
        doSuspendableAnswer { call ->
            delay(1_000)
            recorded += Triple(call.getArgument<String>(0), call.getArgument<Int>(1), call.getArgument<Long>(2))
            1L
        }.whenever(readings).record(any(), any(), any())
        val scope = CoroutineScope(coroutineContext + Job())
        val actions = OdometerActions(scope, garage, readings, clock)
        actions.save("civic", 52_388)
        runCurrent()
        assertTrue(actions.saving.value)
        assertTrue(recorded.isEmpty())
        scope.cancel()
        advanceUntilIdle()
        assertEquals(listOf(Triple("civic", 52_388, 100L)), recorded)
        assertEquals(OdometerNoticeModel(52_388, 100), actions.notice.value)
        assertFalse(actions.saving.value)
        assertFalse(actions.failed.value)
    }

    @Test fun `vehicle actions use the garage without writing a reading`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings, clock)
        actions.add("Civic"); runCurrent()
        actions.select("civic"); runCurrent()
        verify(garage).addVehicle("Civic", 100)
        verify(garage).selectVehicle("civic")
        verifyNoInteractions(readings)
    }
    @Test fun `a slow write still shows the notice for ten seconds`() = runTest {
        val tapAt = 100L
        val completionAt = tapAt + 11_000L
        val schedulerClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(tapAt + testScheduler.currentTime)
        }
        // A repository answer runs on the test scheduler and suspends the append for eleven seconds.
        doSuspendableAnswer { delay(11_000); 1L }
            .whenever(readings).record(any(), any(), any())
        val actions = OdometerActions(backgroundScope, garage, readings, schedulerClock)
        actions.save("civic", 52_388)
        runCurrent()
        advanceTimeBy(11_000)
        assertTrue(actions.saving.value)
        assertNull(actions.notice.value)
        runCurrent()
        verify(readings).record("civic", 52_388, tapAt)
        val notice = actions.notice.value!!
        assertEquals(completionAt, notice.shownAt)
        assertTrue(notice.visibleAt(schedulerClock.millis()))
        advanceTimeBy(9_999)
        assertTrue(notice.visibleAt(schedulerClock.millis()))
        advanceTimeBy(1)
        assertFalse(notice.visibleAt(schedulerClock.millis()))
    }

    @Test fun `an unreadable garage shows the write failure`() = runTest {
        whenever(garage.addVehicle(any(), any())).thenThrow(IllegalStateException("vehicles unreadable"))
        val actions = OdometerActions(backgroundScope, garage, readings, clock)
        actions.add("Civic")
        runCurrent()
        assertTrue(actions.failed.value)
        assertFalse(actions.saving.value)
        assertNull(actions.notice.value)
    }

}
