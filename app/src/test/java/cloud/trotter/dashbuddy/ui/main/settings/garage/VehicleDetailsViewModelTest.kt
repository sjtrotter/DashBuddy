package cloud.trotter.dashbuddy.ui.main.settings.garage

import cloud.trotter.dashbuddy.domain.evaluation.VehicleEconomyInputs
import cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy
import androidx.lifecycle.SavedStateHandle
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.vehicle.DashVehicleRepository
import cloud.trotter.dashbuddy.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.state.vehicle.VehicleSwapLock
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
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
class VehicleDetailsViewModelTest {
    private val garage: GarageRepository = mock()
    private val preferences: AppPreferencesRepository = mock()
    private val lock: VehicleSwapLock = mock()
    private val dashVehicles: DashVehicleRepository = mock()
    private val readings: OdometerReadingRepository = mock()
    private val odometer: OdometerRepository = mock()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC)
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun fresh(id: String) = VehicleDetailsViewModel(SavedStateHandle(mapOf(Screen.VehicleEdit.ARG to id)),
        garage, preferences, clock, lock, dashVehicles, readings, odometer)

    private fun model(id: String, stored: Garage, economy: UserEconomy = UserEconomy()): VehicleDetailsViewModel {
        whenever(garage.garage).thenReturn(flowOf(stored))
        whenever(preferences.sharedEconomy).thenReturn(flowOf(SharedEconomy.from(economy)))
        whenever(dashVehicles.stamps).thenReturn(flowOf(emptyList()))
        whenever(readings.readings).thenReturn(flowOf(emptyList()))
        whenever(odometer.totalMilesFlow).thenReturn(flowOf(null))
        return fresh(id)
    }
    @Test fun `retired vehicle loads facts and restore writes and completes`() = runTest {
        val target = Vehicle("old", "Old", 1, retiredAt = 2)
        val vm = model("old", Garage(listOf(target), null, true))
        runCurrent()
        assertTrue(vm.loaded.value)
        assertTrue(vm.retired.value)

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
        assertEquals(1, vm.pending.value)
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
        val vm = fresh("car")
        runCurrent()

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

        assertEquals(5L, vm.facts.value!!.retiredAt)
    }

    @Test fun `another vehicle's facts ignore the active vehicle's annual mileage`() = runTest {
        val active = Vehicle("a", "A", 1, GasCar(fixed = FixedCosts(expectedAnnualMiles = 20_000.0)))
        val viewed = Vehicle("b", "B", 2, GasCar(fixed = FixedCosts(insuranceDeltaPerMonth = 100.0)))
        model("b", Garage(listOf(active, viewed), "a", true),
            economy = UserEconomy(expectedAnnualMiles = 20_000.0))
        whenever(preferences.sharedEconomy).thenReturn(flowOf(SharedEconomy(expectedAnnualMiles = 10_000.0)))
        val fresh = fresh("b")
        runCurrent()
        val expected = VehicleEconomyInputs.from(viewed.profile, SharedEconomy(expectedAnnualMiles = 10_000.0)).fixedPerMile
        assertEquals(expected, fresh.facts.value!!.fixedPerMile, 1e-9)
        assertEquals(0.12, fresh.facts.value!!.fixedPerMile, 1e-9)
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
        assertEquals(0, vm.pending.value)
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
    }

    @Test fun `retiring a vehicle the repository refuses reports gone and stays`() = runTest {
        val target = Vehicle("target", "Commute", 1)
        val vm = model("target", Garage(listOf(target), "target", true))
        runCurrent()
        whenever(garage.retire("target", 1234)).thenReturn(false)
        vm.retire { fail("A refused retire must not complete") }
        runCurrent()
        assertEquals(GarageWriteFailure.GONE, vm.failure.value)
        assertEquals(1, vm.pending.value)
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
        assertEquals(0, vm.pending.value)
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

    @Test fun `economy follows this stored vehicle and outside profile and shared changes without writes`() = runTest {
        val target = Vehicle("car", "Car", 1, GasCar(wear = WearProfile(tireSetCost = 700.0)))
        model("car", Garage(listOf(target), "other", true))
        val stored = MutableStateFlow(Garage(listOf(target), "other", true))
        val shared = MutableStateFlow(SharedEconomy(electricityPricePerKwh = .20))
        whenever(garage.garage).thenReturn(stored)
        whenever(preferences.sharedEconomy).thenReturn(shared)
        val vm = fresh("car")
        runCurrent()
        assertEquals(700.0, vm.economy.value!!.tireSetCost, 0.0)
        stored.value = stored.value.copy(vehicles = listOf(target.copy(profile = GasCar(wear = WearProfile(tireSetCost = 900.0)))))
        shared.value = SharedEconomy(electricityPricePerKwh = .31)
        runCurrent()
        assertEquals(900.0, vm.economy.value!!.tireSetCost, 0.0)
        assertEquals(.31, vm.economy.value!!.electricityPricePerKwh, 0.0)
        verify(garage, never()).updateProfile(any(), any<(VehicleProfile) -> VehicleProfile>())
        verify(garage, never()).rename(any(), anyOrNull())
    }

    @Test fun `tire edit transforms the profile at write time keeping concurrent oil and identity`() = runTest {
        val profile = GasCar(year = "2020", make = "Honda", model = "Civic", wear = WearProfile(oilCost = 50.0))
        val vm = model("car", Garage(listOf(Vehicle("car", null, 1, profile)), "other", true))
        runCurrent()
        var atWrite: VehicleProfile = profile
        whenever(garage.updateProfile(eq("car"), any<(VehicleProfile) -> VehicleProfile>())).doAnswer {
            val transform = it.getArgument<(VehicleProfile) -> VehicleProfile>(1)
            atWrite = transform(atWrite)
            true
        }
        vm.setTires(800.0, 50000.0)
        atWrite = profile.copy(wear = profile.wear.copy(oilCost = 85.0, oilIntervalMi = 6000.0))
        runCurrent()
        assertEquals(profile.copy(wear = atWrite.wear), atWrite)
        assertEquals(800.0, atWrite.wear.tireSetCost)
        assertEquals(50000.0, atWrite.wear.tireLifetimeMi)
        assertEquals(85.0, atWrite.wear.oilCost)
        assertEquals(6000.0, atWrite.wear.oilIntervalMi)
    }

    @Test fun `partial oil edits and a queued depreciation price followed by toggle preserve untouched values`() = runTest {
        val original = GasCar(wear = WearProfile(oilCost = 60.0, oilIntervalMi = 5000.25),
            fixed = FixedCosts(includeDepreciation = true, purchasePrice = 10000.25, totalLifetimeMi = 200000.25))
        val vm = model("car", Garage(listOf(Vehicle("car", null, 1, original)), "other", true))
        runCurrent()
        var stored: VehicleProfile = original
        whenever(garage.updateProfile(eq("car"), any<(VehicleProfile) -> VehicleProfile>())).doAnswer {
            stored = it.getArgument<(VehicleProfile) -> VehicleProfile>(1)(stored)
            true
        }
        vm.setOil(65.0, null)
        vm.setDepreciationValues(12000.0, null)
        vm.setDepreciationInclude(false)
        runCurrent()
        assertEquals(original.copy(wear = original.wear.copy(oilCost = 65.0),
            fixed = original.fixed.copy(includeDepreciation = false, purchasePrice = 12000.0)), stored)
        vm.setOil(null, 6000.0)
        vm.setDepreciationValues(null, 180000.0)
        runCurrent()
        assertEquals(65.0, stored.wear.oilCost)
        assertEquals(6000.0, stored.wear.oilIntervalMi)
        assertEquals(false, stored.fixed.includeDepreciation)
        assertEquals(12000.0, stored.fixed.purchasePrice)
        assertEquals(180000.0, stored.fixed.totalLifetimeMi)
        verify(preferences, never()).updateOilCost(anyOrNull(), anyOrNull())
    }

    @Test fun `electricity edits only shared preference and annual miles only this profile`() = runTest {
        val vm = model("ev", Garage(listOf(Vehicle("ev", null, 1, ElectricCar())), "other", true))
        runCurrent()
        clearInvocations(garage, preferences)
        vm.setElectricityPrice(.24)
        runCurrent()
        verify(preferences).updateElectricityPrice(.24)
        verifyNoInteractions(garage)
        vm.setExpectedAnnualMiles(17000.0)
        runCurrent()
        val transform = argumentCaptor<(VehicleProfile) -> VehicleProfile>()
        verify(garage).updateProfile(eq("ev"), transform.capture())
        val original = ElectricCar(wear = WearProfile(tireSetCost = 800.0))
        assertEquals(original.copy(fixed = original.fixed.copy(expectedAnnualMiles = 17000.0)), transform.firstValue(original))
        verify(preferences, never()).updateExpectedAnnualMi(any())
    }

    @Test fun `rename debounces and flushes once on blur or leaving`() = runTest {
        val vm = model("car", Garage(listOf(Vehicle("car", "Old", 1)), "car", true))
        whenever(garage.rename(eq("car"), anyOrNull())).thenReturn(true)
        runCurrent()
        vm.nameFocusChanged(true)
        vm.setName("N")
        advanceTimeBy(300)
        vm.setName("New")
        advanceTimeBy(399)
        runCurrent()
        verify(garage, never()).rename(any(), anyOrNull())
        advanceTimeBy(1)
        runCurrent()
        verify(garage).rename("car", "New")
        vm.setName("   ")
        vm.nameFocusChanged(false)
        runCurrent()
        verify(garage).rename("car", null)
        vm.setName("Leaving")
        vm.flushName()
        vm.awaitIdle()
        advanceTimeBy(1000)
        runCurrent()
        verify(garage, times(1)).rename("car", "Leaving")
    }

    @Test fun `retired vehicle has restore and rejects all cost and name writes`() = runTest {
        val vm = model("car", Garage(listOf(Vehicle("car", "Old", 1, retiredAt = 2)), null, true))
        whenever(garage.restore("car")).thenReturn(true)
        runCurrent()
        clearInvocations(garage, preferences)
        vm.setTires(100.0, 1000.0)
        vm.setElectricityPrice(.24)
        vm.setName("Renamed")
        vm.flushName()
        runCurrent()
        assertTrue(vm.retired.value)
        verifyNoInteractions(garage, preferences)
        var done = false
        vm.restore { done = true }
        runCurrent()
        assertTrue(done)
        verify(garage).restore("car")
    }

    @Test fun `missing id and unreadable garage disable writes`() = runTest {
        for (readable in listOf(true, false)) {
            val vm = model("missing", Garage(emptyList(), null, readable))
            runCurrent()
            assertEquals(if (readable) GarageWriteFailure.GONE else GarageWriteFailure.UNREADABLE, vm.failure.value)
            clearInvocations(garage)
            vm.setTires(100.0, 1000.0)
            vm.setName("Name")
            vm.retire { fail() }
            runCurrent()
            verifyNoInteractions(garage)
        }
    }

    @Test fun `profile refusal reports gone and cannot enqueue further edits`() = runTest {
        val vm = model("car", Garage(listOf(Vehicle("car", null, 1)), "car", true))
        whenever(garage.updateProfile(eq("car"), any<(VehicleProfile) -> VehicleProfile>())).thenReturn(false)
        runCurrent()
        vm.setOil(25.0, 5000.0)
        runCurrent()
        assertEquals(GarageWriteFailure.GONE, vm.failure.value)
        assertEquals(GarageWriteFailure.GONE, vm.awaitIdle())
        vm.setTires(800.0, 50000.0)
        runCurrent()
        verify(garage, times(1)).updateProfile(eq("car"), any<(VehicleProfile) -> VehicleProfile>())
    }
}
