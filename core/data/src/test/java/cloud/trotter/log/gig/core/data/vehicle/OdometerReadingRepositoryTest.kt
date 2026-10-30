package cloud.trotter.log.gig.core.data.vehicle

import cloud.trotter.log.gig.core.data.event.AppEventRepo
import cloud.trotter.log.gig.core.data.location.OdometerRepository
import cloud.trotter.log.gig.domain.model.event.*
import cloud.trotter.log.gig.domain.model.event.payload.OdometerReadingPayload
import cloud.trotter.log.gig.domain.model.vehicle.LastOdometerReading
import cloud.trotter.log.gig.domain.model.vehicle.VehicleOdometerReading
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class OdometerReadingRepositoryTest {
    private val events: AppEventRepo = mock()
    private val gps: OdometerRepository = mock()
    private val clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
    @Before fun setup() { whenever(events.getSequencedEventsByType(AppEventType.ODOMETER_READING)).thenReturn(flowOf(emptyList())) }
    @Test fun `appends with null session and gps stamp`() = runTest {
        whenever(gps.totalMiles()).thenReturn(123.5)
        whenever(events.appendUserEvent(any(), isNull())).thenReturn(42L)
        val repo = OdometerReadingRepository(events, gps, clock)
        assertEquals(42L, repo.record("civic", 52_388))
        verify(events).appendUserEvent(AppEvent(AppEventType.ODOMETER_READING, 100, null,
            OdometerReadingPayload("civic", 52_388, 123.5)), null)
        verify(gps).totalMiles()
    }
    @Test fun `rejects out of range`() = runTest {
        val repo = OdometerReadingRepository(events, gps, clock)
        listOf(-1, 0, 1_000_000).forEach {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.record("civic", it) } }
        }
        verify(events, never()).appendUserEvent(any(), anyOrNull())
        verifyNoInteractions(gps)
    }
    @Test fun `readings retain the durable event sequence and skip missing payloads`() = runTest {
        val reading = AppEvent(AppEventType.ODOMETER_READING, 200, null,
            OdometerReadingPayload("civic", 52_388, 123.5))
        whenever(events.getSequencedEventsByType(AppEventType.ODOMETER_READING)).thenReturn(flowOf(listOf(
            SequencedAppEvent(42, reading), SequencedAppEvent(43, reading.copy(payload = null)))))
        val repo = OdometerReadingRepository(events, gps, clock)
        assertEquals(listOf(VehicleOdometerReading("civic", LastOdometerReading(52_388, 123.5, 200), 42)),
            repo.readings.first())
    }
    @Test fun `unloaded gps stamp remains null`() = runTest {
        whenever(gps.totalMiles()).thenReturn(null)
        OdometerReadingRepository(events, gps, clock).record("civic", 1)
        val captured = argumentCaptor<AppEvent>()
        verify(events).appendUserEvent(captured.capture(), isNull())
        assertEquals(OdometerReadingPayload("civic", 1), captured.firstValue.payload)
    }
}
