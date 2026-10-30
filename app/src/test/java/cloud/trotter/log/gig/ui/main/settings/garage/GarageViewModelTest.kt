package cloud.trotter.log.gig.ui.main.settings.garage

import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.data.vehicle.VehiclesUnreadableException
import cloud.trotter.log.gig.state.vehicle.VehicleSwapLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class GarageViewModelTest {
    private val updates = MutableStateFlow(Garage(emptyList(), null, true))
    private val repo: GarageRepository = mock { on { garage } doReturn updates }
    private val lock: VehicleSwapLock = mock()

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `select delegates to repository`() = runTest {
        whenever(repo.selectVehicle("one")).thenReturn(true)
        val vm = GarageViewModel(repo, lock)
        vm.select("one")
        assertTrue(vm.busy.value)
        runCurrent()
        verify(repo).selectVehicle("one")
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
        assertFalse(vm.busy.value)
    }

    @Test fun `swapLocked delegates to the shared lock each time`() {
        val vm = GarageViewModel(repo, lock)
        whenever(lock.locked()).thenReturn(false, true)
        assertFalse(vm.swapLocked())
        assertTrue(vm.swapLocked())
        verify(lock, times(2)).locked()
    }

    @Test fun `garage is null until subscribed and follows repository updates`() = runTest {
        val vm = GarageViewModel(repo, lock)
        assertNull(vm.garage.value)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.garage.collect {} }
        runCurrent()
        assertEquals(updates.value, vm.garage.value)
        updates.value = Garage(emptyList(), null, false)
        runCurrent()
        assertFalse(vm.garage.value!!.readable)
    }

    @Test fun `refused write leaves failure unchanged`() = runTest {
        whenever(repo.selectVehicle("stale")).thenReturn(false)
        val vm = GarageViewModel(repo, lock)
        vm.select("stale")
        runCurrent()
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
        whenever(repo.selectVehicle("one")).thenThrow(IllegalArgumentException("private name")).thenReturn(true)
        vm.select("one")
        runCurrent()
        assertEquals(GarageWriteFailure.RETRY, vm.failure.value)
        vm.select("stale")
        runCurrent()
        assertEquals(GarageWriteFailure.RETRY, vm.failure.value)
        vm.select("one")
        runCurrent()
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
    }

    @Test fun `unreadable exception sets unreadable failure`() = runTest {
        whenever(repo.selectVehicle(any())).thenThrow(VehiclesUnreadableException())
        val vm = GarageViewModel(repo, lock)
        vm.select("one")
        runCurrent()
        assertEquals(GarageWriteFailure.UNREADABLE, vm.failure.value)
        assertFalse(vm.busy.value)
    }

    @Test fun `second tap while busy is ignored`() = runTest {
        val release = CompletableDeferred<Boolean>()
        whenever(repo.selectVehicle("one")).doSuspendableAnswer { release.await() }
        val vm = GarageViewModel(repo, lock)
        vm.select("one")
        vm.select("two")
        runCurrent()
        vm.select("three")
        assertTrue(vm.busy.value)
        release.complete(true)
        runCurrent()
        verify(repo).selectVehicle("one")
        verify(repo, never()).selectVehicle("two")
        verify(repo, never()).selectVehicle("three")
        assertFalse(vm.busy.value)
    }
}
