package cloud.trotter.log.gig.state.vehicle

import cloud.trotter.log.gig.core.state.StateManagerV2
import cloud.trotter.log.gig.domain.state.*
import cloud.trotter.log.gig.feature.bubble.hud.odometer.OdometerCue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class VehicleSwapLockTest {
    private val now = 1_700_000_000_000L
    private val state = MutableStateFlow(AppState())
    private val manager: StateManagerV2 = mock()
    init { whenever(manager.state).thenReturn(state) }
    private val lock = VehicleSwapLock(manager, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC))

    @Test fun `reads the current state across all platforms including paused dashes`() {
        assertFalse(lock.locked())
        val edge = Session("edge", now - OdometerCue.WINDOW_MS)
        state.value = AppState(regions = Regions(platforms = mapOf(
            Platform.DoorDash to PlatformRegion(Platform.DoorDash, Mode.Online, edge))))
        assertFalse(lock.locked())
        state.value = AppState(regions = Regions(platforms = mapOf(
            Platform.DoorDash to PlatformRegion(Platform.DoorDash, Mode.Online, Session("new", now)),
            Platform.Uber to PlatformRegion(Platform.Uber, Mode.Paused, edge.copy(startedAt = edge.startedAt - 1)))))
        assertTrue(lock.locked())
        state.value = AppState()
        assertFalse(lock.locked())
    }
}
