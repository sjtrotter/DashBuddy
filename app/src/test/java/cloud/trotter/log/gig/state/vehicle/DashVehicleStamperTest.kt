package cloud.trotter.log.gig.state.vehicle

import cloud.trotter.log.gig.core.data.vehicle.*
import cloud.trotter.log.gig.core.state.StateManagerV2
import cloud.trotter.log.gig.domain.model.vehicle.*
import cloud.trotter.log.gig.domain.state.*
import cloud.trotter.log.gig.feature.bubble.hud.odometer.OdometerCue
import cloud.trotter.log.gig.ui.bubble.BubbleManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DashVehicleStamperTest {
    private val stateManager: StateManagerV2 = mock()
    private val garageRepository: GarageRepository = mock()
    private val repository: DashVehicleRepository = mock()
    private val bubble: BubbleManager = mock()
    private val clock: Clock = mock()
    private val malibu = Vehicle("malibu", "Malibu", 0)
    private val civic = Vehicle("civic", "Civic", 0)
    private val garage = MutableStateFlow(Garage(listOf(malibu, civic), malibu.id, true))
    private val stamps = MutableStateFlow<List<DashVehicleStamp>>(emptyList())
    private val state = MutableStateFlow(AppState(regions = Regions(platforms = mapOf(
        Platform.DoorDash to PlatformRegion(Platform.DoorDash, Mode.Online, Session("dash", 100))))))
    private var now = 100L

    @Before fun setup() = runTest {
        whenever(stateManager.state).thenReturn(state)
        whenever(garageRepository.garage).thenReturn(garage)
        whenever(repository.stamps).thenReturn(stamps)
        whenever(clock.millis()).thenAnswer { now }
        doSuspendableAnswer { invocation ->
            val next = (stamps.value.size + 1).toLong()
            stamps.value += DashVehicleStamp(invocation.getArgument(0), invocation.getArgument(1),
                10.0, now, next, invocation.getArgument(2))
            next
        }.whenever(repository).record(any(), anyOrNull(), any())
    }
    private fun stamper() = DashVehicleStamper(stateManager, garageRepository, repository, bubble, clock,
        RuntimeEnvironment.getApplication())

    @Test fun `new dash gets one start despite another state emission`() = runTest {
        stamper().start(backgroundScope)
        runCurrent()
        state.value = state.value.copy(regions = state.value.regions.copy(platforms = mapOf(
            Platform.DoorDash to PlatformRegion(Platform.DoorDash, Mode.Paused, Session("dash", 100)))))
        runCurrent()
        verify(repository, times(1)).record("dash", "malibu", DashVehicleKind.START)
        assertEquals(1, stamps.value.size)
        verifyNoInteractions(bubble)
    }
    @Test fun `swap within window writes swap and posts this dash`() = runTest {
        stamper().start(backgroundScope)
        runCurrent()
        now += OdometerCue.WINDOW_MS
        garage.value = garage.value.copy(activeId = civic.id)
        runCurrent()
        verify(repository).record("dash", "civic", DashVehicleKind.SWAP)
        verify(bubble).postVehicleLine("dash", "This shift: Civic")
        assertEquals("civic", DashVehicles.perDash(stamps.value).single().vehicleId)
    }
    @Test fun `after window next dash is posted once per session and vehicle with no swap`() = runTest {
        stamper().start(backgroundScope)
        runCurrent()
        now += OdometerCue.WINDOW_MS + 1
        garage.value = garage.value.copy(activeId = civic.id)
        runCurrent()
        // An unreadable-to-readable transition emits the same candidate again.
        garage.value = garage.value.copy(readable = false)
        runCurrent()
        garage.value = garage.value.copy(readable = true)
        runCurrent()
        verify(bubble, times(1)).postVehicleLine("dash", "Civic from your next shift")
        verify(repository, never()).record(any(), anyOrNull(), eq(DashVehicleKind.SWAP))
        assertEquals(1, stamps.value.size)
    }
    @Test fun `no active vehicle writes one unknown start and posts nothing`() = runTest {
        garage.value = Garage(emptyList(), null, true)
        stamper().start(backgroundScope)
        runCurrent()
        state.value = state.value.copy(regions = state.value.regions.copy(platforms = mapOf(
            Platform.DoorDash to PlatformRegion(Platform.DoorDash, Mode.Paused, Session("dash", 100)))))
        runCurrent()
        verify(repository, times(1)).record("dash", null, DashVehicleKind.START)
        assertEquals(listOf(DashVehicleStamp("dash", null, 10.0, 100, 1, DashVehicleKind.START)), stamps.value)
        verifyNoInteractions(bubble)
    }
    @Test fun `unreadable garage writes unknown start without a notice`() = runTest {
        garage.value = garage.value.copy(readable = false)
        stamper().start(backgroundScope)
        runCurrent()
        verify(repository, times(1)).record("dash", null, DashVehicleKind.START)
        assertNull(stamps.value.single().vehicleId)
        verifyNoInteractions(bubble)
    }
    @Test fun `unknown start then vehicle chosen inside window writes swap and posts this dash`() = runTest {
        garage.value = garage.value.copy(activeId = null)
        stamper().start(backgroundScope)
        runCurrent()
        now += OdometerCue.WINDOW_MS
        garage.value = garage.value.copy(activeId = civic.id)
        runCurrent()
        verify(repository).record("dash", null, DashVehicleKind.START)
        verify(repository).record("dash", "civic", DashVehicleKind.SWAP)
        verify(bubble).postVehicleLine("dash", "This shift: Civic")
        assertEquals("civic", DashVehicles.perDash(stamps.value).single().vehicleId)
    }
    @Test fun `unknown start then vehicle added after eleven minutes only posts next dash`() = runTest {
        garage.value = Garage(emptyList(), null, true)
        stamper().start(backgroundScope)
        runCurrent()
        now += 11 * 60_000
        garage.value = Garage(listOf(civic), civic.id, true)
        runCurrent()
        verify(repository).record("dash", null, DashVehicleKind.START)
        verify(repository, never()).record(any(), anyOrNull(), eq(DashVehicleKind.SWAP))
        assertEquals(1, stamps.value.size)
        assertNull(stamps.value.single().vehicleId)
        verify(bubble).postVehicleLine("dash", "Civic from your next shift")
    }
    @Test fun `a failed emission does not stop later emissions`() = runTest {
        doThrow(IllegalStateException("private detail")).whenever(repository).record("dash", "malibu", DashVehicleKind.START)
        stamper().start(backgroundScope)
        runCurrent()
        garage.value = garage.value.copy(activeId = civic.id)
        runCurrent()
        verify(repository).record("dash", "civic", DashVehicleKind.START)
        assertEquals("civic", stamps.value.single().vehicleId)
    }
}
