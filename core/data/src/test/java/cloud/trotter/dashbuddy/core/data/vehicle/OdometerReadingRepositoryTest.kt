package cloud.trotter.dashbuddy.core.data.vehicle

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.domain.model.event.*
import cloud.trotter.dashbuddy.domain.model.event.payload.OdometerReadingPayload
import cloud.trotter.dashbuddy.domain.model.vehicle.LastOdometerReading
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
    @Before fun setup() { whenever(events.getEventsByType(AppEventType.ODOMETER_READING)).thenReturn(flowOf(emptyList())) }
    @Test fun `appends with null session and gps stamp`() = runTest {
        whenever(gps.totalMiles()).thenReturn(123.5)
        whenever(events.appendUserEvent(any(), isNull())).thenReturn(42L)
        val repo = OdometerReadingRepository(events, gps)
        assertEquals(42L, repo.record("civic", 52_388, 100))
        verify(events).appendUserEvent(AppEvent(AppEventType.ODOMETER_READING, 100, null,
            OdometerReadingPayload("civic", 52_388, 123.5)), null)
        verify(gps).totalMiles()
    }
    @Test fun `rejects out of range`() = runTest {
        val repo = OdometerReadingRepository(events, gps)
        listOf(-1, 0, 1_000_000).forEach {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.record("civic", it, 100) } }
        }
        verify(events, never()).appendUserEvent(any(), anyOrNull())
        verifyNoInteractions(gps)
    }
    @Test fun `last reading is the latest for that vehicle only`() = runTest {
        fun event(id: String, at: Long, miles: Int) = AppEvent(AppEventType.ODOMETER_READING, at, null,
            OdometerReadingPayload(id, miles, at.toDouble()))
        whenever(events.getEventsByType(AppEventType.ODOMETER_READING)).thenReturn(flowOf(listOf(
            event("civic", 200, 52_388), event("truck", 300, 90_000), event("civic", 100, 52_140),
            AppEvent(AppEventType.ODOMETER_READING, 400, null))))
        val repo = OdometerReadingRepository(events, gps)
        assertEquals(LastOdometerReading(52_388, 200.0, 200), repo.lastReading("civic").first())
        assertNull(repo.lastReading("missing").first())
    }
    @Test fun `unloaded gps stamp remains null`() = runTest {
        whenever(gps.totalMiles()).thenReturn(null)
        OdometerReadingRepository(events, gps).record("civic", 1, 100)
        val captured = argumentCaptor<AppEvent>()
        verify(events).appendUserEvent(captured.capture(), isNull())
        assertEquals(OdometerReadingPayload("civic", 1), captured.firstValue.payload)
    }
}
