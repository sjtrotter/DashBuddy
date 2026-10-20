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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
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
        verify(garage, never()).updateName(any(), any())
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
        vm.setTireCost(800.0); vm.setTireLifetime(50000.0)
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
        vm.setOilCost(65.0)
        vm.setPurchasePrice(12000.0)
        vm.setDepreciationInclude(false)
        runCurrent()
        assertEquals(original.copy(wear = original.wear.copy(oilCost = 65.0),
            fixed = original.fixed.copy(includeDepreciation = false, purchasePrice = 12000.0)), stored)
        vm.setOilInterval(6000.0)
        vm.setLifetimeMiles(180000.0)
        runCurrent()
        assertEquals(65.0, stored.wear.oilCost)
        assertEquals(6000.0, stored.wear.oilIntervalMi)
        assertEquals(false, stored.fixed.includeDepreciation)
        assertEquals(12000.0, stored.fixed.purchasePrice)
        assertEquals(180000.0, stored.fixed.totalLifetimeMi)
    }

    @Test fun `annual miles edits only this profile`() = runTest {
        val vm = model("ev", Garage(listOf(Vehicle("ev", null, 1, ElectricCar())), "other", true))
        runCurrent()
        clearInvocations(garage, preferences)
        vm.setExpectedAnnualMiles(17000.0)
        runCurrent()
        val transform = argumentCaptor<(VehicleProfile) -> VehicleProfile>()
        verify(garage).updateProfile(eq("ev"), transform.capture())
        val original = ElectricCar(wear = WearProfile(tireSetCost = 800.0))
        assertEquals(original.copy(fixed = original.fixed.copy(expectedAnnualMiles = 17000.0)), transform.firstValue(original))
    }

    @Test fun `name finishes only after editing and joins the leave flush`() = runTest {
        val vm = model("car", Garage(listOf(Vehicle("car", "Old", 1)), "car", true))
        whenever(garage.updateName(eq("car"), any())).doAnswer { it.getArgument<(String?) -> String?>(1)("Old"); true }
        runCurrent()
        vm.nameFocusChanged(true)
        vm.nameFocusChanged(false)
        verify(garage, never()).updateName(any(), any())
        vm.setName("New")
        advanceTimeBy(1000)
        verify(garage, never()).updateName(any(), any())
        val flush = async { vm.finishAndAwait() }
        runCurrent()
        assertEquals(GarageWriteFailure.NONE, flush.await())
        verify(garage).updateName(eq("car"), argThat { this("Old") == "New" })
    }

    @Test fun `retired vehicle has restore and rejects all cost and name writes`() = runTest {
        val vm = model("car", Garage(listOf(Vehicle("car", "Old", 1, retiredAt = 2)), null, true))
        whenever(garage.restore("car")).thenReturn(true)
        runCurrent()
        clearInvocations(garage, preferences)
        vm.setTireCost(100.0); vm.setTireLifetime(1000.0)
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
            vm.setTireCost(100.0); vm.setTireLifetime(1000.0)
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
        vm.setOilCost(25.0); vm.setOilInterval(5000.0)
        runCurrent()
        assertEquals(GarageWriteFailure.GONE, vm.failure.value)
        assertEquals(GarageWriteFailure.GONE, vm.awaitIdle())
        vm.setTireCost(800.0); vm.setTireLifetime(50000.0)
        runCurrent()
        verify(garage, times(1)).updateProfile(eq("car"), any<(VehicleProfile) -> VehicleProfile>())
    }
    @Test fun `A stays the target when the active vehicle switches to B with unfinished text`() = runTest {
        val a = Vehicle("a", "A", 1, GasCar(wear = WearProfile(oilIntervalMi = 5000.25)))
        val b = Vehicle("b", "B", 2)
        model("a", Garage(listOf(a, b), "a", true))
        val updates = MutableStateFlow(Garage(listOf(a, b), "a", true))
        whenever(garage.garage).thenReturn(updates)
        val vm = fresh("a")
        runCurrent()
        var saved = a.profile
        whenever(garage.updateProfile(eq("a"), any<(VehicleProfile) -> VehicleProfile>())).doAnswer {
            saved = it.getArgument<(VehicleProfile) -> VehicleProfile>(1)(saved); true
        }
        vm.edits.field("oil", "60", { true }) { vm.setOilCost(it.toDouble()) }
        vm.edits.edit("oil", "65")
        updates.value = updates.value.copy(activeId = "b")
        runCurrent()
        val flush = async { vm.finishAndAwait() }
        runCurrent()
        assertEquals(GarageWriteFailure.NONE, flush.await())
        assertEquals(65.0, saved.wear.oilCost)
        assertEquals(5000.25, saved.wear.oilIntervalMi)
        verify(garage, never()).updateProfile(eq("b"), any<(VehicleProfile) -> VehicleProfile>())
    }

    @Test fun `finished field and leave-time finish survive details scope cancellation`() = runTest {
        val vm = model("car", Garage(listOf(Vehicle("car", "Old", 1)), "car", true))
        runCurrent()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        whenever(garage.updateProfile(eq("car"), any<(VehicleProfile) -> VehicleProfile>())).doSuspendableAnswer {
            release.await()
            val changed = it.getArgument<(VehicleProfile) -> VehicleProfile>(1)(GasCar())
            calls += if (changed.wear.oilCost != null) "oil" else "insurance"
            true
        }
        whenever(garage.updateName(eq("car"), argThat { this("Old") == "New" })).doSuspendableAnswer { calls += "name"; true }
        vm.edits.field("oil", "60", { true }) { vm.setOilCost(it.toDouble()) }
        vm.edits.edit("oil", "65")
        vm.edits.finish("oil")
        vm.edits.field("insurance", "0", { true }) { vm.setInsurance(it.toDouble()) }
        vm.edits.edit("insurance", "10")
        vm.setName("New")
        val leave = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { vm.finishAndAwait() }
        runCurrent()
        vm.viewModelScope.cancel()
        release.complete(Unit)
        runCurrent()
        assertEquals(GarageWriteFailure.NONE, leave.await())
        assertEquals(listOf("oil", "insurance", "name"), calls)
    }

    @Test fun `reset queues after accepted cost and discards dirty text and late blur`() = runTest {
        val original = GasCar(mpg = 42.0, fuelType = FuelType.DIESEL, year = "2020", make = "A", model = "B",
            wear = WearProfile(oilCost = 75.0), fixed = FixedCosts(purchasePrice = 30000.0))
        val vm = model("car", Garage(listOf(Vehicle("car", "Named", 1, original)), "car", true))
        runCurrent()
        val release = CompletableDeferred<Unit>()
        var stored: VehicleProfile = original
        whenever(garage.updateProfile(eq("car"), any<(VehicleProfile) -> VehicleProfile>())).doSuspendableAnswer {
            release.await()
            stored = it.getArgument<(VehicleProfile) -> VehicleProfile>(1)(stored)
            true
        }
        vm.setOilCost(85.0)
        vm.edits.field("oil", "75", { true }) { vm.setOilCost(it.toDouble()) }
        vm.edits.edit("oil", "999")
        val lateBlur = vm.edits.finisher("oil")
        val reset = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { vm.resetEstimates() }
        lateBlur()
        release.complete(Unit)
        runCurrent()
        assertEquals(GarageWriteFailure.NONE, reset.await())
        lateBlur()
        assertTrue(vm.edits.finishAll())
        runCurrent()
        assertEquals(original.copy(wear = WearProfile(), fixed = FixedCosts()), stored)
        verify(garage, never()).updateName(any(), any())
        verify(garage, times(2)).updateProfile(eq("car"), any<(VehicleProfile) -> VehicleProfile>())
    }

    @Test fun `retire saves focused cost and name before rechecking lock and retiring`() = runTest {
        val vm = model("car", Garage(listOf(Vehicle("car", "Old", 1)), "car", true))
        runCurrent()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        whenever(garage.updateProfile(eq("car"), any<(VehicleProfile) -> VehicleProfile>())).doSuspendableAnswer {
            release.await(); it.getArgument<(VehicleProfile) -> VehicleProfile>(1)(GasCar()); order += "cost"; true
        }
        whenever(garage.updateName(eq("car"), argThat { this("Old") == "New" })).doSuspendableAnswer { order += "name"; true }
        whenever(lock.locked()).thenAnswer { order += "lock"; false }
        whenever(garage.retire("car", 1234)).doSuspendableAnswer { order += "retire"; true }
        vm.edits.field("oil", "60", { true }) { vm.setOilCost(it.toDouble()) }
        vm.edits.edit("oil", "65")
        vm.setName("New")
        var done = false
        vm.retire({ done = true })
        runCurrent()
        assertTrue(vm.busy.value)
        assertFalse(vm.edits.acceptingEdits)
        vm.setName("Late")
        assertEquals(WriteResult.REJECTED, vm.setInsurance(99.0).await())
        verify(garage, never()).retire(any(), any())
        release.complete(Unit)
        runCurrent()
        assertTrue(done)
        assertEquals(listOf("cost", "name", "lock", "retire"), order)
    }

    @Test fun `failed focused save reports unsaved and prevents retire`() = runTest {
        val vm = model("car", Garage(listOf(Vehicle("car", "Old", 1)), "car", true))
        runCurrent()
        whenever(garage.updateName(eq("car"), argThat { this("Old") == "New" })).thenThrow(IllegalStateException())
        vm.setName("New")
        var failed = false
        vm.retire({ fail("Failed save must prevent retirement") }, { failed = true })
        runCurrent()
        assertTrue(failed)
        verify(garage, never()).retire(any(), any())
        verify(lock, never()).locked()
    }

    @Test fun `odometer follows stored vehicle and writes immediately without finishing name`() = runTest {
        val bike = Vehicle("bike", "Bike", 1, Bicycle())
        val active = Vehicle("car", "Car", 2)
        model("bike", Garage(listOf(bike, active), "car", true))
        val updates = MutableStateFlow(Garage(listOf(bike, active), "car", true))
        whenever(garage.garage).thenReturn(updates)
        val vm = fresh("bike")
        assertEquals(WriteResult.REJECTED, vm.setOdometerShown(true).await())
        runCurrent()
        assertFalse(vm.vehicle.value!!.showsOdometer)
        vm.setName("Unfinished")
        whenever(garage.updateOdometerShown(eq("bike"), argThat { this(null) == true })).thenReturn(true)
        vm.setOdometerShown(true)
        runCurrent()
        verify(garage).updateOdometerShown(eq("bike"), argThat { this(null) == true })
        verify(garage, never()).updateName(any(), any())
        assertFalse(vm.vehicle.value!!.showsOdometer)
        updates.value = updates.value.copy(vehicles = listOf(bike.copy(odometerShown = true), active))
        runCurrent()
        assertTrue(vm.vehicle.value!!.showsOdometer)
        assertEquals("Unfinished", vm.nameField().text)
        verify(lock, never()).locked()
    }

    @Test fun `odometer refusal and unreadable writes use details failure path`() = runTest {
        for (unreadable in listOf(false, true)) {
            val vm = model("car", Garage(listOf(Vehicle("car", "Car", 1)), "car", true))
            runCurrent()
            if (unreadable) whenever(garage.updateOdometerShown(eq("car"), argThat { this(null) == false }))
                .thenThrow(cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException())
            else whenever(garage.updateOdometerShown(eq("car"), argThat { this(null) == false })).thenReturn(false)
            vm.setOdometerShown(false)
            runCurrent()
            assertEquals(if (unreadable) GarageWriteFailure.UNREADABLE else GarageWriteFailure.GONE, vm.failure.value)
            assertTrue(vm.vehicle.value!!.showsOdometer)
            vm.discardChanges()
        }
    }

    @Test fun `retired and missing vehicles reject odometer writes`() = runTest {
        for (stored in listOf(Garage(emptyList(), null, true),
            Garage(listOf(Vehicle("car", "Old", 1, retiredAt = 2)), null, true))) {
            val vm = model("car", stored)
            runCurrent()
            assertEquals(WriteResult.REJECTED, vm.setOdometerShown(false).await())
        }
        verify(garage, never()).updateOdometerShown(any(), any())
    }

    @Test fun `retrying a failed retirement runs the retained action without a second retirement`() = runTest {
        val vm = model("car", Garage(listOf(Vehicle("car", "Car", 1)), "car", true))
        runCurrent()
        whenever(garage.retire("car", 1234)).thenThrow(IllegalStateException()).thenReturn(true)
        var done = 0
        var failures = 0
        vm.retire({ done++ }, { failures++ })
        runCurrent()
        assertEquals(1, failures)
        assertEquals(0, done)
        vm.retry()
        vm.retire({ done++ }, { failures++ })
        runCurrent()
        verify(garage, times(2)).retire("car", 1234)
        assertEquals(1, done)
    }


    @Test fun `undo oil restores the raw prior override and discards unfinished text without touching interval`() = runTest {
        for (prior in listOf(null, 50.0)) {
            reset(garage)
            // The rendered profile deliberately differs from the write-time profile.
            val vm = model("car", Garage(listOf(Vehicle("car", null, 1)), "car", true))
            runCurrent()
            var stored: VehicleProfile = GasCar(wear = WearProfile(oilCost = prior, oilIntervalMi = 5000.25))
            whenever(garage.updateProfile(eq("car"), any<(VehicleProfile) -> VehicleProfile>())).doAnswer {
                stored = it.getArgument<(VehicleProfile) -> VehicleProfile>(1)(stored); true
            }
            assertEquals(WriteResult.SAVED, vm.setOilCost(65.0).also { runCurrent() }.await())
            val undo = requireNotNull(vm.lastSaveUndo.value)
            assertEquals(prior, undo.priorState)
            vm.edits.field("OIL_COST", "65", { true }) { vm.setOilCost(it.toDouble()) }
            vm.edits.edit("OIL_COST", "999")
            val lateBlur = vm.edits.finisher("OIL_COST")
            val restored = vm.undo(undo)
            lateBlur()
            runCurrent()
            assertEquals(WriteResult.SAVED, restored.await())
            assertEquals(prior, stored.wear.oilCost)
            assertEquals(5000.25, stored.wear.oilIntervalMi)
            assertEquals(prior != null, cloud.trotter.dashbuddy.domain.evaluation.EconomyField.OIL_COST in
                cloud.trotter.dashbuddy.domain.evaluation.EconomyFieldGroups.userSetFields(stored))
            assertFalse(vm.edits.field("OIL_COST", "65", { true }) { vm.setOilCost(it.toDouble()) }.edited)
            assertNull(vm.lastSaveUndo.value)
        }
    }

    @Test fun `any sibling submission invalidates undo before its held write acknowledges`() = runTest {
        val vm = model("car", Garage(listOf(Vehicle("car", null, 1)), "car", true))
        runCurrent()
        var stored: VehicleProfile = GasCar(wear = WearProfile(oilIntervalMi = 5000.25))
        var block = false
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<Double?>()
        whenever(garage.updateProfile(eq("car"), any<(VehicleProfile) -> VehicleProfile>())).doSuspendableAnswer {
            if (block) release.await()
            stored = it.getArgument<(VehicleProfile) -> VehicleProfile>(1)(stored)
            order += stored.wear.oilCost
            true
        }
        vm.setOilCost(65.0)
        runCurrent()
        val firstUndo = requireNotNull(vm.lastSaveUndo.value)
        block = true
        vm.setOilInterval(7000.5)
        runCurrent()
        val restore = vm.undo(firstUndo)
        assertNull(vm.lastSaveUndo.value)
        assertEquals(WriteResult.REJECTED, restore.await())
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(65.0, 65.0), order)
        assertEquals(65.0, stored.wear.oilCost)
        assertEquals(7000.5, stored.wear.oilIntervalMi)
        assertEquals("OIL_INTERVAL", vm.lastSaveUndo.value?.key)
    }

    @Test fun `only latest details save can be undone and leaving reset and retire end the offer`() = runTest {
        val vm = model("car", Garage(listOf(Vehicle("car", null, 1)), "car", true))
        runCurrent()
        var stored: VehicleProfile = GasCar()
        whenever(garage.updateProfile(eq("car"), any<(VehicleProfile) -> VehicleProfile>())).doAnswer {
            stored = it.getArgument<(VehicleProfile) -> VehicleProfile>(1)(stored); true
        }
        vm.setOilCost(65.0); runCurrent()
        val first = requireNotNull(vm.lastSaveUndo.value)
        vm.setInsurance(50.0); runCurrent()
        val second = requireNotNull(vm.lastSaveUndo.value)
        assertNotSame(first, second)
        assertEquals(WriteResult.REJECTED, vm.undo(first).await())
        assertEquals(65.0, stored.wear.oilCost)
        assertEquals(GarageWriteFailure.NONE, vm.finishAndAwait())
        assertNull(vm.lastSaveUndo.value)
        vm.resumeEditing()
        vm.setOilCost(75.0); runCurrent()
        assertNotNull(vm.lastSaveUndo.value)
        assertEquals(GarageWriteFailure.NONE, vm.resetEstimates())
        assertNull(vm.lastSaveUndo.value)
        assertNull(stored.wear.oilCost)
        vm.setOilCost(85.0); runCurrent()
        whenever(garage.retire("car", 1234)).thenReturn(true)
        vm.retire {}
        assertNull(vm.lastSaveUndo.value)
        runCurrent()
        assertNull(vm.lastSaveUndo.value)
    }

    @Test fun `rename undo restores previous name including unnamed without offering another undo`() = runTest {
        for (prior in listOf(null, "Old")) {
            reset(garage)
            val vm = model("car", Garage(listOf(Vehicle("car", prior, 1)), "car", true))
            runCurrent()
            var name = prior
            whenever(garage.updateName(eq("car"), any())).doAnswer {
                name = it.getArgument<(String?) -> String?>(1)(name); true
            }
            vm.setName("New")
            vm.flushName()
            runCurrent()
            assertEquals("New", name)
            val restore = vm.undo(requireNotNull(vm.lastSaveUndo.value))
            runCurrent()
            assertEquals(WriteResult.SAVED, restore.await())
            assertEquals(prior, name)
            assertNull(vm.lastSaveUndo.value)
        }
    }

    @Test fun `failed undo shows not saved and retry restores without a new undo offer`() = runTest {
        val vm = model("car", Garage(listOf(Vehicle("car", null, 1)), "car", true))
        runCurrent()
        var stored: VehicleProfile = GasCar(wear = WearProfile(oilCost = 50.0))
        var failWrite = false
        whenever(garage.updateProfile(eq("car"), any<(VehicleProfile) -> VehicleProfile>())).doAnswer {
            if (failWrite) throw IllegalStateException()
            stored = it.getArgument<(VehicleProfile) -> VehicleProfile>(1)(stored); true
        }
        vm.setOilCost(65.0); runCurrent()
        failWrite = true
        val restore = vm.undo(requireNotNull(vm.lastSaveUndo.value))
        runCurrent()
        assertEquals(WriteResult.FAILED, restore.await())
        assertEquals(GarageWriteFailure.RETRY, vm.failure.value)
        assertEquals(65.0, stored.wear.oilCost)
        failWrite = false
        vm.retry(); runCurrent()
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
        assertEquals(50.0, stored.wear.oilCost)
        assertNull(vm.lastSaveUndo.value)
    }

    @Test fun `oil undo disappears when 70 is submitted and a queued restore cannot revert it`() = runTest {
        val vm = model("car", Garage(listOf(Vehicle("car", null, 1)), "car", true))
        runCurrent()
        var stored: VehicleProfile = GasCar(wear = WearProfile(oilCost = 50.0))
        var release = CompletableDeferred<Unit>().also { it.complete(Unit) }
        whenever(garage.updateProfile(eq("car"), any<(VehicleProfile) -> VehicleProfile>())).doSuspendableAnswer {
            release.await()
            stored = it.getArgument<(VehicleProfile) -> VehicleProfile>(1)(stored)
            true
        }
        vm.setOilCost(65.0); runCurrent()
        val offer = requireNotNull(vm.lastSaveUndo.value)
        release = CompletableDeferred()
        vm.setOilCost(70.0)
        assertNull(vm.lastSaveUndo.value) // synchronous, before the write even starts
        assertEquals(WriteResult.REJECTED, vm.undo(offer).await())
        runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals(70.0, stored.wear.oilCost)

        vm.setOilCost(65.0); runCurrent()
        release = CompletableDeferred()
        val queuedUndo = vm.undo(requireNotNull(vm.lastSaveUndo.value))
        runCurrent() // Restore entered the repository, waiting for its write lock.
        vm.setOilCost(70.0)
        release.complete(Unit); runCurrent()
        assertEquals(WriteResult.CHANGED, queuedUndo.await())
        assertEquals(70.0, stored.wear.oilCost)
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
    }

    @Test fun `oil restore compares current raw value inside the garage transform`() = runTest {
        val vm = model("car", Garage(listOf(Vehicle("car", null, 1)), "car", true))
        runCurrent()
        var stored: VehicleProfile = GasCar(wear = WearProfile(oilCost = 50.0))
        whenever(garage.updateProfile(eq("car"), any<(VehicleProfile) -> VehicleProfile>())).doAnswer {
            stored = it.getArgument<(VehicleProfile) -> VehicleProfile>(1)(stored); true
        }
        vm.setOilCost(65.0); runCurrent()
        stored = cloud.trotter.dashbuddy.core.data.settings.VehicleCostTransforms.oilCost(stored, 70.0)
        val undo = vm.undo(requireNotNull(vm.lastSaveUndo.value)); runCurrent()
        assertEquals(WriteResult.CHANGED, undo.await())
        assertEquals(70.0, stored.wear.oilCost)
    }

}
