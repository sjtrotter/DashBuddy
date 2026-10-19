package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.lifecycle.SavedStateHandle
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.vehicle.ActivateOn
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
        whenever(garage.addVehicle(anyOrNull(), any(), any(), any())).thenReturn(Vehicle("new", null, 1234))
        whenever(garage.updateVehicle(any(), anyOrNull(), any())).thenReturn(true)
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
        verify(garage).updateVehicle("target", "Renamed", profile.copy(mpg = 39.0))
        verify(garage, never()).addVehicle(anyOrNull(), any(), any(), any())
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

    @Test fun `unknown or retired id reports gone at load while unreadable remains distinct`() = runTest {
        val retired = Vehicle("retired", "Retired", 1, retiredAt = 2)
        listOf(
            "missing" to Garage(emptyList(), null, true),
            "retired" to Garage(listOf(retired), null, true),
            null to Garage(emptyList(), null, false),
        ).forEach { (id, stored) ->
            val vm = model(id, stored)
            runCurrent()
            assertEquals(if (stored.readable) GarageWriteFailure.GONE else GarageWriteFailure.UNREADABLE, vm.failure.value)
            assertFalse(vm.loaded.value)
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

    @Test fun `overlong input is refused without a failed save`() = runTest {
        val vehicle = Vehicle("one", "Original", 1)
        val vm = model("one", Garage(listOf(vehicle), "one", true))
        runCurrent()
        vm.setName("😀".repeat(40))
        assertEquals("Original", vm.name.value)
        vm.save {}
        runCurrent()
        verify(garage).updateVehicle("one", "Original", vehicle.profile)
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
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

    @Test fun `refused vehicle update reports gone and keeps editor open`() = runTest {
        val vehicle = Vehicle("one", null, 1)
        val vm = model("one", Garage(listOf(vehicle), "one", true))
        runCurrent()
        whenever(garage.updateVehicle(eq("one"), anyOrNull(), any())).thenReturn(false)
        vm.save { fail("Refused save must not complete") }
        runCurrent()
        assertEquals(GarageWriteFailure.GONE, vm.failure.value)
        assertFalse(vm.busy.value)
        verify(garage, never()).rename(any(), anyOrNull())
        verify(garage, never()).updateProfile(any(), any<VehicleProfile>())
    }

    @Test fun `EV edit returning to stored type preserves tuned costs and fuel`() = runTest {
        val profile = ElectricCar(kwhPer100Mi = 27.0,
            wear = WearProfile(tireSetCost = 812.0), fixed = FixedCosts(purchasePrice = 32000.0))
        val vehicle = Vehicle("ev", "Electric", 1, profile)
        val vm = model("ev", Garage(listOf(vehicle), "ev", true))
        runCurrent()
        vm.draft.updateVehicleType(VehicleType.BICYCLE)
        assertTrue(vm.resetsCosts(VehicleType.BICYCLE))
        vm.draft.updateVehicleType(VehicleType.EV)
        vm.draft.updateConsumption(29.0)
        assertFalse(vm.resetsCosts(VehicleType.EV))
        vm.save {}
        runCurrent()
        val saved = argumentCaptor<VehicleProfile>()
        verify(garage).updateVehicle(eq("ev"), eq("Electric"), saved.capture())
        assertEquals(profile.copy(kwhPer100Mi = 29.0), saved.firstValue)
        assertEquals(profile.gasFuel, saved.firstValue.gasFuel)
    }

    @Test fun `scooter powertrain change on the stored type is saved with its consumption`() = runTest {
        val profile = ScooterMoped(wear = WearProfile(tireSetCost = 150.0))
        val vehicle = Vehicle("scoot", "Scooter", 1, profile)
        val vm = model("scoot", Garage(listOf(vehicle), "scoot", true))
        runCurrent()
        vm.draft.updatePowertrain(Powertrain.ELECTRIC)
        vm.draft.updateConsumption(2.5)
        vm.save {}
        runCurrent()
        val saved = argumentCaptor<VehicleProfile>()
        verify(garage).updateVehicle(eq("scoot"), eq("Scooter"), saved.capture())
        val scooter = saved.firstValue as ScooterMoped
        assertEquals(Powertrain.ELECTRIC, scooter.powertrain)
        assertEquals(2.5, scooter.kwhPer100Mi)
        assertEquals(150.0, scooter.wear.tireSetCost)
    }

    @Test fun `gas edit returning to stored type preserves SUV mpg costs fuel and identity`() = runTest {
        val profile = GasCar(vehicleClass = GasCarClass.SUV, mpg = 20.0, fuelType = FuelType.PREMIUM,
            year = "2020", make = "Toyota", model = "Highlander",
            wear = WearProfile(tireSetCost = 812.0), fixed = FixedCosts(purchasePrice = 32000.0))
        val vehicle = Vehicle("gas", null, 1, profile)
        val vm = model("gas", Garage(listOf(vehicle), "gas", true))
        runCurrent()
        vm.draft.updateVehicleType(VehicleType.BICYCLE)
        vm.draft.updateVehicleType(VehicleType.CAR)
        assertTrue(vm.draft.state.value.userSetFields.isEmpty())
        vm.save {}
        runCurrent()
        verify(garage).updateVehicle("gas", null, profile)
    }

    @Test fun `explicit class after returning to stored type changes only class`() = runTest {
        val profile = GasCar(vehicleClass = GasCarClass.SUV, mpg = 20.0, fuelType = FuelType.PREMIUM,
            wear = WearProfile(tireSetCost = 812.0), fixed = FixedCosts(purchasePrice = 32000.0))
        val vehicle = Vehicle("gas", null, 1, profile)
        val vm = model("gas", Garage(listOf(vehicle), "gas", true))
        runCurrent()
        vm.draft.updateVehicleType(VehicleType.BICYCLE)
        vm.draft.updateVehicleType(VehicleType.CAR)
        vm.draft.updateVehicleClass(VehicleClass.TRUCK)
        assertEquals(setOf(EconomyField.VEHICLE_CLASS), vm.draft.state.value.userSetFields)
        vm.save {}
        runCurrent()
        verify(garage).updateVehicle("gas", null, profile.copy(vehicleClass = GasCarClass.TRUCK))
    }

    @Test fun `explicit mpg after returning to stored type changes only mpg`() = runTest {
        val profile = GasCar(vehicleClass = GasCarClass.SUV, mpg = 20.0, fuelType = FuelType.PREMIUM,
            wear = WearProfile(tireSetCost = 812.0), fixed = FixedCosts(purchasePrice = 32000.0))
        val vehicle = Vehicle("gas", null, 1, profile)
        val vm = model("gas", Garage(listOf(vehicle), "gas", true))
        runCurrent()
        vm.draft.updateVehicleType(VehicleType.BICYCLE)
        vm.draft.updateVehicleType(VehicleType.CAR)
        vm.draft.updateEstimatedMpg(23f)
        assertEquals(setOf(EconomyField.VEHICLE_MPG), vm.draft.state.value.userSetFields)
        vm.save {}
        runCurrent()
        verify(garage).updateVehicle("gas", null, profile.copy(mpg = 23.0))
    }

    @Test fun `returning to stored electric type preserves unedited consumption`() = runTest {
        for (profile in listOf(ElectricCar(kwhPer100Mi = 27.0), EBike(whPerMile = 18.0))) {
            val vehicle = Vehicle("electric", null, 1, profile)
            val vm = model("electric", Garage(listOf(vehicle), "electric", true))
            runCurrent()
            clearInvocations(garage)
            vm.draft.updateVehicleType(VehicleType.BICYCLE)
            vm.draft.updateVehicleType(profile.vehicleType)
            vm.save {}
            runCurrent()
            verify(garage).updateVehicle("electric", null, profile)
        }
    }

    @Test fun `identity edit after returning to stored type preserves class and mpg`() = runTest {
        val profile = GasCar(vehicleClass = GasCarClass.SUV, mpg = 20.0,
            year = "2020", make = "Toyota", model = "Highlander")
        val vehicle = Vehicle("gas", null, 1, profile)
        val vm = model("gas", Garage(listOf(vehicle), "gas", true), lookupAllowed = true)
        whenever(lookup.getMakes("2021")).thenReturn(emptyList())
        runCurrent()
        vm.draft.updateVehicleType(VehicleType.BICYCLE)
        vm.draft.updateVehicleType(VehicleType.CAR)
        vm.draft.onYearSelected("2021")
        vm.save {}
        runCurrent()
        verify(garage).updateVehicle("gas", null, profile.copy(year = "2021", make = null, model = null))
    }

    @Test fun `type change saves presets instead of stored costs`() = runTest {
        val profile = GasCar(fuelType = FuelType.PREMIUM,
            wear = WearProfile(tireSetCost = 812.0), fixed = FixedCosts(purchasePrice = 32000.0))
        val vehicle = Vehicle("gas", null, 1, profile)
        val vm = model("gas", Garage(listOf(vehicle), "gas", true))
        runCurrent()
        vm.draft.updateVehicleType(VehicleType.BICYCLE)
        assertTrue(vm.resetsCosts(VehicleType.BICYCLE))
        vm.save {}
        runCurrent()
        verify(garage).updateVehicle("gas", null, Bicycle())
    }

    private suspend fun TestScope.lookUpDiesel(vm: VehicleEditViewModel) {
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

    @Test fun `looked up diesel trim saves diesel over stored fuel`() = runTest {
        val vehicle = Vehicle("gas", null, 1, GasCar(fuelType = FuelType.PREMIUM))
        val vm = model("gas", Garage(listOf(vehicle), "gas", true), lookupAllowed = true)
        runCurrent()
        lookUpDiesel(vm)
        vm.save {}
        runCurrent()
        verify(garage).updateVehicle("gas", null,
            GasCar(year = "2020", make = "Ford", model = "Truck", mpg = 28.0, fuelType = FuelType.DIESEL))
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
}
