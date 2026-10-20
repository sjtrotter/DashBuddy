package cloud.trotter.dashbuddy.ui.main.settings.garage

import cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.vehicle.ActivateOn
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
class VehicleAddViewModelTest {
    private val garage: GarageRepository = mock()
    private val lookup: VehicleRepository = mock()
    private val preferences: AppPreferencesRepository = mock()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC)
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private suspend fun model(
        stored: Garage = Garage(emptyList(), null, true),
        economy: UserEconomy = UserEconomy(),
        lookupAllowed: Boolean = false,
    ): VehicleAddViewModel {
        whenever(garage.garage).thenReturn(flowOf(stored))
        whenever(garage.addVehicle(anyOrNull(), any(), any(), any())).thenReturn(Vehicle("new", null, 1234))
        whenever(garage.updateVehicle(any(), anyOrNull(), any())).thenReturn(true)
        whenever(preferences.sharedEconomy).thenReturn(flowOf(SharedEconomy.from(economy)))
        whenever(preferences.vehicleLookupAllowed).thenReturn(flowOf(lookupAllowed))
        whenever(lookup.getYears()).thenReturn(listOf("2020", "2021"))
        return VehicleAddViewModel(garage, lookup, preferences, clock)
    }
    @Test fun `add saves the draft EV consumption and trimmed name without legacy mirrors`() = runTest {
        val vm = model()
        assertTrue(vm.busy.value)
        runCurrent()
        clearInvocations(preferences)
        vm.setName("  Electric  ")
        vm.draft.updateVehicleType(VehicleType.EV)
        vm.draft.updateConsumption(28.5)
        var done = 0
        vm.save { done++ }
        runCurrent()
        verify(garage).addVehicle("Electric", 1234, ElectricCar(kwhPer100Mi = 28.5), ActivateOn.IF_NONE_ACTIVE)
        verifyNoInteractions(preferences)
        assertEquals(1, done)
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
    }

    @Test fun `bicycle add has no gas and blank name is null`() = runTest {
        val vm = model()
        runCurrent()
        clearInvocations(preferences)
        vm.setName("   ")
        vm.draft.updateVehicleType(VehicleType.BICYCLE)
        vm.save {}
        runCurrent()
        verify(garage).addVehicle(null, 1234, Bicycle(), ActivateOn.IF_NONE_ACTIVE)
        assertNull(vm.draft.state.value.profile().gasMpg)
        verifyNoInteractions(preferences)
    }

    @Test fun `electricity price is written only when user set and no mirrors are written`() = runTest {
        val vm = model()
        runCurrent()
        clearInvocations(preferences)
        vm.draft.updateVehicleType(VehicleType.EV)
        vm.draft.updateElectricityPrice(.24)
        vm.save {}
        runCurrent()
        verify(preferences).updateElectricityPrice(.24)
        verifyNoMoreInteractions(preferences)
    }

    @Test fun `retry after tariff failure does not add the vehicle twice`() = runTest {
        val vm = model()
        runCurrent()
        vm.draft.updateVehicleType(VehicleType.EV)
        vm.draft.updateElectricityPrice(.24)
        whenever(preferences.updateElectricityPrice(.24)).thenThrow(IllegalStateException()).thenReturn(Unit)
        var done = 0
        vm.save { done++ }
        runCurrent()
        assertEquals(0, done)
        assertEquals(GarageWriteFailure.RETRY, vm.failure.value)
        vm.save { done++ }
        runCurrent()
        verify(garage, times(1)).addVehicle(null, 1234, ElectricCar(), ActivateOn.IF_NONE_ACTIVE)
        verify(garage).updateVehicle("new", null, ElectricCar())
        assertEquals(1, done)
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
    }

    @Test fun `allow lookup persists consent before fetching years`() = runTest {
        val vm = model()
        runCurrent()
        verify(lookup, never()).getYears()
        val persisted = CompletableDeferred<Unit>()
        whenever(preferences.setVehicleLookupAllowed(true)).doSuspendableAnswer { persisted.await() }
        vm.allowLookup()
        runCurrent()
        assertFalse(vm.draft.state.value.lookupAllowed)
        verify(lookup, never()).getYears()
        persisted.complete(Unit)
        runCurrent()
        inOrder(preferences, lookup) {
            verify(preferences).setVehicleLookupAllowed(true)
            verify(lookup).getYears()
        }
        assertTrue(vm.draft.state.value.lookupAllowed)
        assertEquals(listOf("2020", "2021"), vm.draft.state.value.availableYears)
    }

    @Test fun `failed consent never fetches years`() = runTest {
        val vm = model()
        runCurrent()
        whenever(preferences.setVehicleLookupAllowed(true)).thenThrow(IllegalStateException())
        vm.allowLookup()
        runCurrent()
        assertFalse(vm.draft.state.value.lookupAllowed)
        assertEquals(GarageWriteFailure.RETRY, vm.failure.value)
        verifyNoInteractions(lookup)
    }

    @Test fun `write failure keeps draft and second tap while busy is ignored`() = runTest {
        val vm = model()
        runCurrent()
        vm.setName("Keep me")
        vm.draft.updateVehicleType(VehicleType.EV)
        vm.draft.updateConsumption(26.0)
        val release = CompletableDeferred<Vehicle>()
        whenever(garage.addVehicle(anyOrNull(), any(), any(), any())).doSuspendableAnswer { release.await() }
        var done = false
        vm.save { done = true }
        vm.save { done = true }
        runCurrent()
        assertTrue(vm.busy.value)
        release.completeExceptionally(IllegalStateException("private name"))
        runCurrent()
        verify(garage, times(1)).addVehicle("Keep me", 1234, ElectricCar(kwhPer100Mi = 26.0), ActivateOn.IF_NONE_ACTIVE)
        assertFalse(done)
        assertFalse(vm.busy.value)
        assertEquals(GarageWriteFailure.RETRY, vm.failure.value)
        assertEquals("Keep me", vm.name.value)
        assertEquals(26.0, vm.draft.state.value.kwhPer100Mi, 0.0)
    }

    @Test fun `unreadable write keeps editor open`() = runTest {
        val vm = model()
        runCurrent()
        whenever(garage.addVehicle(anyOrNull(), any(), any(), any())).thenThrow(VehiclesUnreadableException())
        vm.save { fail("Failed save must not complete") }
        runCurrent()
        assertEquals(GarageWriteFailure.UNREADABLE, vm.failure.value)
    }

    @Test fun `40 code point paste is capped and saves`() = runTest {
        val vm = model()
        runCurrent()
        vm.setName("😀".repeat(40).cappedVehicleName())
        assertEquals("😀".repeat(Vehicle.MAX_NAME_CODE_POINTS), vm.name.value)
        var done = false
        vm.save { done = true }
        runCurrent()
        verify(garage).addVehicle(vm.name.value, 1234, GasCar(), ActivateOn.IF_NONE_ACTIVE)
        assertTrue(done)
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
    }

    @Test fun `changing identity after lookup does not save the old fuel grade`() = runTest {
        val vm = model(lookupAllowed = true)
        runCurrent()
        lookUpDiesel(vm)
        whenever(lookup.getMakes("2021")).thenReturn(emptyList())
        vm.draft.onYearSelected("2021")
        vm.save {}
        runCurrent()
        val saved = argumentCaptor<VehicleProfile>()
        verify(garage).addVehicle(isNull(), eq(1234L), saved.capture(), eq(ActivateOn.IF_NONE_ACTIVE))
        assertEquals(FuelType.REGULAR, saved.firstValue.gasFuel)
    }

    @Test fun `add delegates conditional activation to repository without rereading garage`() = runTest {
        val vehicle = Vehicle("active", null, 1)
        val vm = model(stored = Garage(listOf(vehicle), "active", true))
        runCurrent()
        clearInvocations(garage)
        vm.save {}
        runCurrent()
        verify(garage).addVehicle(null, 1234, GasCar(), activateOn = ActivateOn.IF_NONE_ACTIVE)
        verifyNoMoreInteractions(garage)
    }

    private suspend fun TestScope.lookUpDiesel(vm: VehicleAddViewModel) {
        whenever(lookup.getMakes("2020")).thenReturn(listOf("Ford"))
        whenever(lookup.getModels("2020", "Ford")).thenReturn(listOf("Truck"))
        whenever(lookup.getVehicleOptions("2020", "Ford", "Truck"))
            .thenReturn(listOf(VehicleOption("diesel", "Diesel trim")))
        whenever(lookup.getVehicleDetails("diesel")).thenReturn(VehicleDetails(28f, FuelType.DIESEL))
        vm.draft.onYearSelected("2020")
        runCurrent()
        vm.draft.onMakeSelected("Ford")
        runCurrent()
        vm.draft.onModelSelected("Truck")
        runCurrent()
        vm.draft.onTrimSelected("Diesel trim")
        runCurrent()
    }

}
