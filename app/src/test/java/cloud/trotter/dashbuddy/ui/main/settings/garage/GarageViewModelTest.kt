package cloud.trotter.dashbuddy.ui.main.settings.garage

import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class GarageViewModelTest {
    private val updates = MutableStateFlow(Garage(emptyList(), null, true))
    private val repo: GarageRepository = mock { on { garage } doReturn updates }
    private val clock = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC)

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `select and retire delegate with the clock time`() = runTest {
        whenever(repo.selectVehicle("one")).thenReturn(true)
        whenever(repo.retire("one", 1234)).thenReturn(true)
        val vm = GarageViewModel(repo, clock)
        vm.select("one")
        assertTrue(vm.busy.value)
        runCurrent()
        vm.retire("one")
        runCurrent()
        verify(repo).selectVehicle("one")
        verify(repo).retire("one", 1234)
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
        assertFalse(vm.busy.value)
    }

    @Test fun `garage is null until subscribed and follows repository updates`() = runTest {
        val vm = GarageViewModel(repo, clock)
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
        val vm = GarageViewModel(repo, clock)
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
        whenever(repo.retire(any(), any())).thenThrow(VehiclesUnreadableException())
        val vm = GarageViewModel(repo, clock)
        vm.retire("one")
        runCurrent()
        assertEquals(GarageWriteFailure.UNREADABLE, vm.failure.value)
        assertFalse(vm.busy.value)
    }

    @Test fun `second tap while busy is ignored`() = runTest {
        val release = CompletableDeferred<Boolean>()
        whenever(repo.selectVehicle("one")).doSuspendableAnswer { release.await() }
        val vm = GarageViewModel(repo, clock)
        vm.select("one")
        vm.select("two")
        runCurrent()
        vm.retire("one")
        assertTrue(vm.busy.value)
        release.complete(true)
        runCurrent()
        verify(repo).selectVehicle("one")
        verify(repo, never()).selectVehicle("two")
        verify(repo, never()).retire(any(), any())
        assertFalse(vm.busy.value)
    }
}
