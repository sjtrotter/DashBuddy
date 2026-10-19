package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.lifecycle.SavedStateHandle
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
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
class VehicleEditViewModelTest {
    private val garage: GarageRepository = mock()
    private val lookup: VehicleRepository = mock()
    private val preferences: AppPreferencesRepository = mock()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC)

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private suspend fun model(
        id: String? = null,
        stored: Garage = Garage(emptyList(), null, true),
        economy: UserEconomy = UserEconomy(),
        lookupAllowed: Boolean = false,
    ): VehicleEditViewModel {
        whenever(garage.garage).thenReturn(flowOf(stored))
        whenever(garage.addVehicle(anyOrNull(), any(), any())).thenReturn(Vehicle("new", null, 1234))
        whenever(garage.updateProfile(any(), any<VehicleProfile>())).thenReturn(true)
        whenever(garage.rename(any(), anyOrNull())).thenReturn(true)
        whenever(preferences.userEconomy).thenReturn(flowOf(economy))
        whenever(preferences.vehicleLookupAllowed).thenReturn(flowOf(lookupAllowed))
        whenever(lookup.getYears()).thenReturn(listOf("2020", "2021"))
        return VehicleEditViewModel(SavedStateHandle(id?.let { mapOf(Screen.VehicleEdit.ARG to it) } ?: emptyMap()),
            garage, lookup, preferences, clock)
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
        verify(garage).addVehicle("Electric", 1234, ElectricCar(kwhPer100Mi = 28.5))
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
        verify(garage).addVehicle(null, 1234, Bicycle())
        assertNull(vm.draft.state.value.profile().gasMpg)
        verifyNoInteractions(preferences)
    }

    @Test fun `edit loads its own identity mpg and user fields despite another active vehicle`() = runTest {
        val profile = GasCar(vehicleClass = GasCarClass.COMPACT, mpg = 37.0,
            year = "2020", make = "Toyota", model = "Corolla",
            wear = WearProfile(tireSetCost = 700.0), fixed = FixedCosts(purchasePrice = 20000.0))
        val target = Vehicle("target", "Commute", 10, profile)
        val active = Vehicle("active", "Other", 20, ElectricCar(kwhPer100Mi = 50.0))
        val vm = model("target", Garage(listOf(target, active), "active", true),
            UserEconomy(kwhPer100Mi = 50.0, userSetFields = setOf(EconomyField.KWH_PER_100_MI)), lookupAllowed = true)
        runCurrent()
        assertTrue(vm.isEditing)
        assertEquals("Commute", vm.name.value)
        val state = vm.draft.state.value
        assertEquals(profile, state.profile())
        assertEquals("2020", state.year)
        assertEquals("Toyota", state.make)
        assertEquals("Corolla", state.model)
        assertEquals("", state.trim)
        assertEquals(37f, state.mpg)
        assertEquals(setOf(EconomyField.VEHICLE_CLASS, EconomyField.VEHICLE_MPG), state.userSetFields)
        assertEquals(listOf("2020", "2021"), state.availableYears)
        clearInvocations(preferences)
        vm.setName("  Renamed  ")
        vm.draft.updateEstimatedMpg(39f)
        vm.save {}
        runCurrent()
        inOrder(garage) {
            verify(garage).updateProfile("target", profile.copy(mpg = 39.0))
            verify(garage).rename("target", "Renamed")
        }
        verify(garage, never()).addVehicle(anyOrNull(), any(), any())
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
        verify(garage, times(1)).addVehicle(null, 1234, ElectricCar())
        verify(garage).updateProfile("new", ElectricCar())
        verify(garage).rename("new", null)
        assertEquals(1, done)
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
    }

    @Test fun `stored user set electricity price survives edit`() = runTest {
        val target = Vehicle("ev", "Electric", 10, ElectricCar(kwhPer100Mi = 27.0))
        val vm = model("ev", Garage(listOf(target), "ev", true),
            UserEconomy(electricityPricePerKwh = .31, userSetFields = setOf(EconomyField.ELECTRICITY_PRICE)))
        runCurrent()
        assertEquals(setOf(EconomyField.KWH_PER_100_MI, EconomyField.ELECTRICITY_PRICE), vm.draft.state.value.userSetFields)
        assertEquals(27.0, vm.draft.state.value.kwhPer100Mi, 0.0)
        clearInvocations(preferences)
        vm.save {}
        runCurrent()
        verify(preferences).updateElectricityPrice(.31)
        verifyNoMoreInteractions(preferences)
    }

    @Test fun `unknown or retired id and unreadable garage disable save`() = runTest {
        val retired = Vehicle("retired", "Retired", 1, retiredAt = 2)
        listOf(
            "missing" to Garage(emptyList(), null, true),
            "retired" to Garage(listOf(retired), null, true),
            null to Garage(emptyList(), null, false),
        ).forEach { (id, stored) ->
            val vm = model(id, stored)
            runCurrent()
            assertEquals(GarageWriteFailure.UNREADABLE, vm.failure.value)
            clearInvocations(garage)
            vm.save { fail("Unreadable editor must not complete") }
            runCurrent()
            verifyNoInteractions(garage)
        }
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
        whenever(garage.addVehicle(anyOrNull(), any(), any())).doSuspendableAnswer { release.await() }
        var done = false
        vm.save { done = true }
        vm.save { done = true }
        runCurrent()
        assertTrue(vm.busy.value)
        release.completeExceptionally(IllegalStateException("private name"))
        runCurrent()
        verify(garage, times(1)).addVehicle("Keep me", 1234, ElectricCar(kwhPer100Mi = 26.0))
        assertFalse(done)
        assertFalse(vm.busy.value)
        assertEquals(GarageWriteFailure.RETRY, vm.failure.value)
        assertEquals("Keep me", vm.name.value)
        assertEquals(26.0, vm.draft.state.value.kwhPer100Mi, 0.0)
    }

    @Test fun `unreadable write keeps editor open`() = runTest {
        val vm = model()
        runCurrent()
        whenever(garage.addVehicle(anyOrNull(), any(), any())).thenThrow(VehiclesUnreadableException())
        vm.save { fail("Failed save must not complete") }
        runCurrent()
        assertEquals(GarageWriteFailure.UNREADABLE, vm.failure.value)
    }

    @Test fun `overlong rename is rejected before profile write`() = runTest {
        val vehicle = Vehicle("one", "Original", 1)
        val vm = model("one", Garage(listOf(vehicle), "one", true))
        runCurrent()
        clearInvocations(garage)
        vm.setName("😀".repeat(Vehicle.MAX_NAME_CODE_POINTS + 1))
        vm.draft.updateEstimatedMpg(40f)
        vm.save { fail("Invalid name must not complete") }
        runCurrent()
        assertEquals(GarageWriteFailure.RETRY, vm.failure.value)
        verifyNoInteractions(garage)
    }

    @Test fun `refused profile update does not rename or close editor`() = runTest {
        val vehicle = Vehicle("one", null, 1)
        val vm = model("one", Garage(listOf(vehicle), "one", true))
        runCurrent()
        whenever(garage.updateProfile(eq("one"), any<VehicleProfile>())).thenReturn(false)
        vm.save { fail("Refused save must not complete") }
        runCurrent()
        verify(garage, never()).rename(any(), anyOrNull())
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
    }
}
