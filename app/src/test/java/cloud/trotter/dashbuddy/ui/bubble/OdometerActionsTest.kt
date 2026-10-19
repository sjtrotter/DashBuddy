package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerNoticeModel
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
    private val gps: OdometerRepository = mock()
    private val clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)

    @Test fun `successful append publishes the saved notice and refreshes gps`() = runTest {
        whenever(gps.totalMiles()).thenReturn(123.5)
        val actions = OdometerActions(backgroundScope, garage, readings, gps, clock)
        actions.save("civic", 52_388)
        assertTrue(actions.saving.value)
        runCurrent()
        verify(readings).record("civic", 52_388, 100)
        assertEquals(OdometerNoticeModel(52_388, 100), actions.notice.value)
        assertEquals(123.5, actions.gpsTotalMiles.value!!, 0.0)
        assertFalse(actions.saving.value)
        assertFalse(actions.failed.value)
    }

    @Test fun `failed append keeps the sheet available for retry`() = runTest {
        whenever(readings.record(any(), any(), any())).thenThrow(IllegalStateException("storage unavailable"))
        val actions = OdometerActions(backgroundScope, garage, readings, gps, clock)
        actions.save("civic", 52_388)
        runCurrent()
        assertNull(actions.notice.value)
        assertTrue(actions.failed.value)
        assertFalse(actions.saving.value)
        actions.refresh()
        assertFalse(actions.failed.value)
    }

    @Test fun `double tap appends only once`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings, gps, clock)
        actions.save("civic", 52_388)
        actions.save("civic", 52_388)
        runCurrent()
        verify(readings, times(1)).record("civic", 52_388, 100)
    }

    @Test fun `vehicle actions use the garage without writing a reading`() = runTest {
        val actions = OdometerActions(backgroundScope, garage, readings, gps, clock)
        actions.add("Civic"); runCurrent()
        actions.select("civic"); runCurrent()
        verify(garage).addVehicle("Civic", 100)
        verify(garage).selectVehicle("civic")
        verifyNoInteractions(readings)
    }
}
