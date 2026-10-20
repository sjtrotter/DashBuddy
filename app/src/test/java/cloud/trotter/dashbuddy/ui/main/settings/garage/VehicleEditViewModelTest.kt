package cloud.trotter.dashbuddy.ui.main.settings.garage

import cloud.trotter.dashbuddy.domain.evaluation.VehicleEconomyInputs
import cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy
import androidx.lifecycle.SavedStateHandle
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.vehicle.DashVehicleRepository
import cloud.trotter.dashbuddy.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.dashbuddy.core.data.vehicle.ActivateOn
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.state.vehicle.VehicleSwapLock
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
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
    private val lock: VehicleSwapLock = mock()
    private val dashVehicles: DashVehicleRepository = mock()
    private val readings: OdometerReadingRepository = mock()
    private val odometer: OdometerRepository = mock()
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
        whenever(preferences.sharedEconomy).thenReturn(flowOf(SharedEconomy.from(economy)))
        whenever(preferences.vehicleLookupAllowed).thenReturn(flowOf(lookupAllowed))
        whenever(lookup.getYears()).thenReturn(listOf("2020", "2021"))
        whenever(dashVehicles.stamps).thenReturn(flowOf(emptyList()))
        whenever(readings.readings).thenReturn(flowOf(emptyList()))
        whenever(odometer.totalMilesFlow).thenReturn(flowOf(null))
        return VehicleEditViewModel(SavedStateHandle(id?.let { mapOf(Screen.VehicleEdit.ARG to it) } ?: emptyMap()),
            garage, lookup, preferences, clock, lock, dashVehicles, readings, odometer)
    }

    @Test fun `retired vehicle loads facts and restore writes and completes`() = runTest {
        val target = Vehicle("old", "Old", 1, retiredAt = 2)
        val vm = model("old", Garage(listOf(target), null, true))
        runCurrent()
        assertTrue(vm.loaded.value)
        assertTrue(vm.retired.value)
        assertFalse(vm.isActive.value)
        assertEquals(2L, vm.facts.value!!.retiredAt)
        assertEquals(VehicleUsage(0, null), vm.facts.value!!.usage)
        whenever(garage.restore("old")).thenReturn(true)
        var done = 0
        vm.restore { done++ }
        vm.restore { fail("Busy restore must not complete") }
        runCurrent()
        verify(garage, times(1)).restore("old")
        verify(garage, never()).selectVehicle(any())
        verify(lock, never()).locked()
        assertEquals(1, done)
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
    }

    @Test fun `refused restore reports gone and keeps details open`() = runTest {
        val target = Vehicle("old", "Old", 1, retiredAt = 2)
        val vm = model("old", Garage(listOf(target), null, true))
        runCurrent()
        whenever(garage.restore("old")).thenReturn(false)
        vm.restore { fail("Refused restore must not complete") }
        runCurrent()
        assertEquals(GarageWriteFailure.GONE, vm.failure.value)
        assertFalse(vm.busy.value)
    }

    @Test fun `facts are null in Add and restore does nothing`() = runTest {
        val vm = model()
        runCurrent()
        assertNull(vm.facts.value)
        assertFalse(vm.retired.value)
        assertFalse(vm.isActive.value)
        vm.restore { fail("Add must not restore") }
        runCurrent()
        verify(garage, never()).restore(any())
    }

    @Test fun `facts follow shared costs stamps GPS and latest reading for the loaded vehicle`() = runTest {
        val target = Vehicle("car", "Car", 1, GasCar(mpg = 25.0))
        val vmEconomy = MutableStateFlow(SharedEconomy(gasPricePerGallon = 3.0))
        val stamps = MutableStateFlow(listOf(DashVehicleStamp("dash", "car", 10.0, 1, 1, DashVehicleKind.START)))
        val gps = MutableStateFlow<Double?>(30.0)
        val readingsFlow = MutableStateFlow(listOf(VehicleOdometerReading("other", LastOdometerReading(99, null, 3), 3),
            VehicleOdometerReading("car", LastOdometerReading(52388, 20.0, 2), 2)))
        val garageFlow = MutableStateFlow(Garage(listOf(target), "car", true))
        whenever(garage.garage).thenReturn(garageFlow)
        whenever(preferences.sharedEconomy).thenReturn(vmEconomy)
        whenever(preferences.vehicleLookupAllowed).thenReturn(flowOf(false))
        whenever(dashVehicles.stamps).thenReturn(stamps)
        whenever(odometer.totalMilesFlow).thenReturn(gps)
        whenever(readings.readings).thenReturn(readingsFlow)
        val vm = VehicleEditViewModel(SavedStateHandle(mapOf(Screen.VehicleEdit.ARG to "car")),
            garage, lookup, preferences, clock, lock, dashVehicles, readings, odometer)
        runCurrent()
        assertTrue(vm.isActive.value)
        assertEquals(VehicleUsage(1, 20.0), vm.facts.value!!.usage)
        assertEquals(52388, vm.facts.value!!.lastReading!!.readingMiles)
        assertEquals(0.12, vm.facts.value!!.energyPerMile, 0.0)
        gps.value = 40.0
        vmEconomy.value = SharedEconomy(gasPricePerGallon = 4.0)
        runCurrent()
        assertEquals(VehicleUsage(1, 30.0), vm.facts.value!!.usage)
        assertEquals(0.16, vm.facts.value!!.energyPerMile, 0.0)
        stamps.value = stamps.value + DashVehicleStamp("other", null, 35.0, 3, 3, DashVehicleKind.START)
        readingsFlow.value = readingsFlow.value + VehicleOdometerReading("car", LastOdometerReading(52400, 40.0, 4), 4)
        runCurrent()
        assertEquals(VehicleUsage(1, 25.0), vm.facts.value!!.usage)
        assertEquals(52400, vm.facts.value!!.lastReading!!.readingMiles)
        garageFlow.value = Garage(listOf(target.copy(profile = GasCar(mpg = 50.0))), "car", true)
        runCurrent()
        assertEquals(0.08, vm.facts.value!!.energyPerMile, 0.0)
        garageFlow.value = garageFlow.value.copy(vehicles = listOf(target.copy(retiredAt = 5)), activeId = null)
        runCurrent()
        assertTrue(vm.retired.value)
        assertFalse(vm.isActive.value)
        assertEquals(5L, vm.facts.value!!.retiredAt)
    }

    /** #1383 review: the page builds a car's figures from the shared settings, never the active car's overrides. */
    @Test fun `another vehicle's facts ignore the active vehicle's annual mileage`() = runTest {
        val active = Vehicle("a", "A", 1, GasCar(fixed = FixedCosts(expectedAnnualMiles = 20_000.0)))
        val viewed = Vehicle("b", "B", 2, GasCar(fixed = FixedCosts(insuranceDeltaPerMonth = 100.0)))
        model("b", Garage(listOf(active, viewed), "a", true),
            economy = UserEconomy(expectedAnnualMiles = 20_000.0))
        whenever(preferences.sharedEconomy).thenReturn(flowOf(SharedEconomy(expectedAnnualMiles = 10_000.0)))
        val fresh = VehicleEditViewModel(SavedStateHandle(mapOf(Screen.VehicleEdit.ARG to "b")),
            garage, lookup, preferences, clock, lock, dashVehicles, readings, odometer)
        runCurrent()
        val expected = VehicleEconomyInputs.from(viewed.profile, SharedEconomy(expectedAnnualMiles = 10_000.0)).fixedPerMile
        assertEquals(expected, fresh.facts.value!!.fixedPerMile, 1e-9)
        assertEquals(0.12, fresh.facts.value!!.fixedPerMile, 1e-9)
    }

    /** #1383 review: Save merges onto the vehicle as stored now, so an Edit costs round trip survives a rename. */
    @Test fun `save after an economy edit keeps the newly stored cost`() = runTest {
        val before = Vehicle("car", "Car", 1, GasCar(wear = WearProfile(tireSetCost = 800.0)))
        val garageFlow = MutableStateFlow(Garage(listOf(before), "car", true))
        model("car", Garage(listOf(before), "car", true))
        whenever(garage.garage).thenReturn(garageFlow)
        val fresh = VehicleEditViewModel(SavedStateHandle(mapOf(Screen.VehicleEdit.ARG to "car")),
            garage, lookup, preferences, clock, lock, dashVehicles, readings, odometer)
        runCurrent()
        garageFlow.value = Garage(listOf(before.copy(profile = GasCar(wear = WearProfile(tireSetCost = 1000.0)))), "car", true)
        runCurrent()
        fresh.setName("Renamed")
        fresh.save {}
        runCurrent()
        val saved = argumentCaptor<VehicleProfile>()
        verify(garage).updateVehicle(eq("car"), eq("Renamed"), saved.capture())
        assertEquals(1000.0, saved.firstValue.wear.tireSetCost)
    }

    @Test fun `retire writes clock time and completes once`() = runTest {
        val target = Vehicle("target", "Commute", 1)
        val vm = model("target", Garage(listOf(target), "target", true))
        runCurrent()
        whenever(garage.retire("target", 1234)).thenReturn(true)
        var done = 0
        vm.retire { done++ }
        runCurrent()
        verify(garage).retire("target", 1234)
        assertEquals(1, done)
        assertFalse(vm.busy.value)
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
    }

    /** Review r1 (Sol): a vehicle already retired elsewhere reports GONE instead of a silent, enabled screen. */
    @Test fun `retiring a vehicle the repository refuses reports gone and stays`() = runTest {
        val target = Vehicle("target", "Commute", 1)
        val vm = model("target", Garage(listOf(target), "target", true))
        runCurrent()
        whenever(garage.retire("target", 1234)).thenReturn(false)
        vm.retire { fail("A refused retire must not complete") }
        runCurrent()
        assertEquals(GarageWriteFailure.GONE, vm.failure.value)
        assertFalse(vm.busy.value)
    }

    @Test fun `retiring active vehicle while locked reports lock without a write or completion`() = runTest {
        val target = Vehicle("target", "Commute", 1)
        val vm = model("target", Garage(listOf(target), "target", true))
        runCurrent()
        whenever(lock.locked()).thenReturn(true)
        val reported = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.retireLocked.first() }
        vm.retire { fail("Locked retire must not complete") }
        runCurrent()
        assertTrue(reported.isCompleted)
        verify(garage, never()).retire(any(), any())
        assertFalse(vm.busy.value)
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
    }

    @Test fun `non active vehicle retires while locked`() = runTest {
        val target = Vehicle("target", "Commute", 1)
        val active = Vehicle("active", "Other", 2)
        val vm = model("target", Garage(listOf(target, active), "active", true))
        runCurrent()
        whenever(lock.locked()).thenReturn(true)
        whenever(garage.retire("target", 1234)).thenReturn(true)
        var done = false
        val reported = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.retireLocked.first() }
        vm.retire { done = true }
        runCurrent()
        verify(garage).retire("target", 1234)
        assertTrue(done)
        assertFalse(reported.isCompleted)
    }

    @Test fun `retire checks current active vehicle rather than the loaded selection`() = runTest {
        val target = Vehicle("target", "Commute", 1)
        val other = Vehicle("other", "Other", 2)
        val stored = Garage(listOf(target, other), "other", true)
        val vm = model("target", stored)
        runCurrent()
        val updates = MutableStateFlow(stored.copy(activeId = "target"))
        whenever(garage.garage).thenReturn(updates)
        whenever(lock.locked()).thenReturn(true)
        vm.retire { fail("Now-active vehicle must not retire while locked") }
        runCurrent()
        verify(garage, never()).retire(any(), any())
    }

    @Test fun `retire failures stay in editor and use writer failure vocabulary`() = runTest {
        val target = Vehicle("target", "Commute", 1)
        val vm = model("target", Garage(listOf(target), "target", true))
        runCurrent()
        whenever(garage.retire(any(), any())).thenThrow(IllegalStateException()).thenThrow(VehiclesUnreadableException())
        vm.retire { fail("Failed retire must not complete") }
        runCurrent()
        assertEquals(GarageWriteFailure.RETRY, vm.failure.value)
        vm.retire { fail("Unreadable retire must not complete") }
        runCurrent()
        assertEquals(GarageWriteFailure.UNREADABLE, vm.failure.value)
    }

    @Test fun `retire is inert in Add and while loading busy or unreadable`() = runTest {
        val target = Vehicle("target", "Commute", 1)
        val vm = model("target", Garage(listOf(target), "target", true))
        vm.retire { fail("Loading editor must not complete") }
        runCurrent()
        val release = CompletableDeferred<Boolean>()
        whenever(garage.retire(any(), any())).doSuspendableAnswer { release.await() }
        vm.retire {}
        vm.retire { fail("Second retire must not complete") }
        runCurrent()
        verify(garage, times(1)).retire("target", 1234)
        release.complete(true)
        runCurrent()
        val add = model()
        runCurrent()
        clearInvocations(garage)
        add.retire { fail("Add must not retire") }
        val unreadable = model("target", Garage(emptyList(), null, false))
        runCurrent()
        clearInvocations(garage)
        unreadable.retire { fail("Unreadable editor must not complete") }
        runCurrent()
        verifyNoInteractions(garage)
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

    @Test fun `unknown id reports gone at load while unreadable remains distinct`() = runTest {
        listOf(
            "missing" to Garage(emptyList(), null, true),
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
