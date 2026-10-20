package cloud.trotter.dashbuddy.core.data.vehicle

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.domain.model.event.*
import cloud.trotter.dashbuddy.domain.model.event.payload.DashVehiclePayload
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class DashVehicleRepositoryTest {
    private val events: AppEventRepo = mock()
    private val gps: OdometerRepository = mock()
    private val clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
    @Before fun setup() { whenever(events.getSequencedEventsByType(AppEventType.DASH_VEHICLE)).thenReturn(flowOf(emptyList())) }
    @Test fun `appends with payload session only gps and kind`() = runTest {
        whenever(gps.totalMiles()).thenReturn(123.5)
        whenever(events.appendUserEvent(any(), isNull())).thenReturn(42L)
        assertEquals(42L, DashVehicleRepository(events, gps, clock).record("dash", "civic", DashVehicleKind.START))
        verify(events).appendUserEvent(AppEvent(AppEventType.DASH_VEHICLE, 100, null,
            DashVehiclePayload("dash", "civic", 123.5, "START")), null)
        verify(gps).totalMiles()
    }
    @Test fun `stamps retain sequence skip missing payloads and map unknown kinds to swap`() = runTest {
        val event = AppEvent(AppEventType.DASH_VEHICLE, 200, null, DashVehiclePayload("dash", "civic", 123.5, "FUTURE"))
        whenever(events.getSequencedEventsByType(AppEventType.DASH_VEHICLE)).thenReturn(flowOf(listOf(
            SequencedAppEvent(42, event), SequencedAppEvent(43, event.copy(payload = null)))))
        assertEquals(listOf(DashVehicleStamp("dash", "civic", 123.5, 200, 42, DashVehicleKind.SWAP)),
            DashVehicleRepository(events, gps, clock).stamps.first())
    }
    @Test fun `unloaded gps stamp remains null`() = runTest {
        whenever(gps.totalMiles()).thenReturn(null)
        DashVehicleRepository(events, gps, clock).record("dash", "civic", DashVehicleKind.SWAP)
        val captured = argumentCaptor<AppEvent>()
        verify(events).appendUserEvent(captured.capture(), isNull())
        assertEquals(DashVehiclePayload("dash", "civic", null, "SWAP"), captured.firstValue.payload)
    }
    @Test fun `unknown vehicle is recorded and read back as null`() = runTest {
        whenever(gps.totalMiles()).thenReturn(123.5)
        val event = AppEvent(AppEventType.DASH_VEHICLE, 100, null, DashVehiclePayload("dash", null, 123.5, "START"))
        whenever(events.getSequencedEventsByType(AppEventType.DASH_VEHICLE)).thenReturn(flowOf(listOf(SequencedAppEvent(42, event))))
        val repository = DashVehicleRepository(events, gps, clock)
        repository.record("dash", null, DashVehicleKind.START)
        verify(events).appendUserEvent(event, null)
        assertEquals(listOf(DashVehicleStamp("dash", null, 123.5, 100, 42, DashVehicleKind.START)), repository.stamps.first())
    }
}
