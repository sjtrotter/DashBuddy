package cloud.trotter.dashbuddy.feature.setup.wizard.vehicle

import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.EconomyFieldGroups
import cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.evaluation.VehicleEconomyInputs
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.feature.setup.wizard.VEHICLE_NOT_LISTED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleDraftTest {
    private val lookup: VehicleRepository = mock()

    @Test
    fun `from maps a stored EV with user set consumption and the stored tariff`() {
        val profile = ElectricCar(kwhPer100Mi = 27.0, wear = WearProfile(tireSetCost = 900.0))
        val shared = SharedEconomy(electricityPricePerKwh = .22,
            userSetFields = setOf(EconomyField.ELECTRICITY_PRICE))
        val economy = UserEconomy.from(VehicleEconomyInputs.from(profile, shared), shared)

        val state = VehicleDraftState.from(profile, economy, "", "", "", "", 1f, false)

        assertEquals(VehicleDraftState(
            profile = profile, vehicleClass = VehicleClass.EV, mpg = 1f,
            kwhPer100Mi = 27.0, whPerMile = VehiclePresets.EBIKE_WH_PER_MILE,
            electricityPricePerKwh = .22,
            userSetFields = setOf(EconomyField.KWH_PER_100_MI, EconomyField.ELECTRICITY_PRICE),
        ), state)
    }

    @Test
    fun `from maps a sedan identity and falls back to the economy profile`() {
        val profile = GasCar(mpg = 32.0, year = "2020", make = "Toyota", model = "Corolla")
        val shared = SharedEconomy()
        val economy = UserEconomy.from(VehicleEconomyInputs.from(profile, shared), shared)
        val expected = VehicleDraftState(
            profile = profile, year = "2020", make = "Toyota", model = "Corolla", trim = "Automatic",
            mpg = 32f, manualMpgEntry = true, lookupAllowed = true,
            kwhPer100Mi = 0.0, userSetFields = setOf(EconomyField.VEHICLE_MPG),
        )

        assertEquals(expected,
            VehicleDraftState.from(profile, economy, "2020", "Toyota", "Corolla", "Automatic", 32f, true))
        assertEquals(expected,
            VehicleDraftState.from(null, economy, "2020", "Toyota", "Corolla", "Automatic", 32f, true))
        assertFalse(VehicleDraftState.from(profile, economy, "2020", "Toyota", " ", "", 32f, false)
            .manualMpgEntry)
    }

    @Test
    fun `late makes answer cannot replace the selected year's makes`() = runTest {
        val oldMakes = CompletableDeferred<List<String>>()
        whenever(lookup.getMakes("2020")).doSuspendableAnswer { oldMakes.await() }
        whenever(lookup.getMakes("2021")).thenReturn(listOf("Toyota"))
        val draft = VehicleDraft(lookup, this,
            VehicleDraftState(lookupAllowed = true, availableYears = listOf("2020", "2021")),
            onShapeChanged = {})

        draft.onYearSelected("2020")
        testScheduler.runCurrent()
        draft.onYearSelected("2021")
        testScheduler.runCurrent()
        assertEquals(listOf(VEHICLE_NOT_LISTED, "Toyota"), draft.state.value.availableMakes)
        oldMakes.complete(listOf("Ford"))
        testScheduler.advanceUntilIdle()

        assertEquals("2021", draft.state.value.year)
        assertEquals(listOf(VEHICLE_NOT_LISTED, "Toyota"), draft.state.value.availableMakes)
    }

    @Test
    fun `late models answer cannot replace the selected make's models`() = runTest {
        val oldModels = CompletableDeferred<List<String>>()
        whenever(lookup.getModels("2024", "Ford")).doSuspendableAnswer { oldModels.await() }
        whenever(lookup.getModels("2024", "Toyota")).thenReturn(listOf("Corolla"))
        val draft = VehicleDraft(lookup, this,
            VehicleDraftState(lookupAllowed = true, year = "2024", availableYears = listOf("2024")),
            onShapeChanged = {})

        draft.onMakeSelected("Ford")
        testScheduler.runCurrent()
        draft.onMakeSelected("Toyota")
        testScheduler.runCurrent()
        oldModels.complete(listOf("Focus"))
        testScheduler.advanceUntilIdle()

        assertEquals("Toyota", draft.state.value.make)
        assertEquals(listOf(VEHICLE_NOT_LISTED, "Corolla"), draft.state.value.availableModels)
    }

    @Test
    fun `late trims answer cannot replace the selected model's trims`() = runTest {
        val oldTrims = CompletableDeferred<List<VehicleOption>>()
        val trims = listOf(VehicleOption("corolla", "Automatic"))
        whenever(lookup.getVehicleOptions("2024", "Toyota", "Camry")).doSuspendableAnswer { oldTrims.await() }
        whenever(lookup.getVehicleOptions("2024", "Toyota", "Corolla")).thenReturn(trims)
        val draft = VehicleDraft(lookup, this,
            VehicleDraftState(lookupAllowed = true, year = "2024", make = "Toyota",
                availableYears = listOf("2024")), onShapeChanged = {})

        draft.onModelSelected("Camry")
        testScheduler.runCurrent()
        draft.onModelSelected("Corolla")
        testScheduler.runCurrent()
        oldTrims.complete(listOf(VehicleOption("camry", "Manual")))
        testScheduler.advanceUntilIdle()

        assertEquals("Corolla", draft.state.value.model)
        assertEquals(listOf(VehicleOption("NOT_LISTED", VEHICLE_NOT_LISTED)) + trims,
            draft.state.value.availableTrims)
    }

    @Test
    fun `load replaces the draft and fetches years only when allowed`() = runTest {
        whenever(lookup.getYears()).thenReturn(listOf("2024"))
        val draft = VehicleDraft(lookup, this,
            VehicleDraftState(year = "2020", availableMakes = listOf("Old make")), onShapeChanged = {})
        val loaded = VehicleDraftState(year = "2024", make = "Toyota", model = "Corolla")

        draft.load(loaded)
        testScheduler.advanceUntilIdle()
        assertEquals(loaded, draft.state.value)
        verifyNoInteractions(lookup)

        draft.load(loaded.copy(lookupAllowed = true))
        testScheduler.advanceUntilIdle()
        assertEquals(loaded.copy(lookupAllowed = true, availableYears = listOf("2024")), draft.state.value)
        verify(lookup).getYears()
        verifyNoMoreInteractions(lookup)
    }

    @Test
    fun `lookup cascades through identity and marks MPG only when supplied`() = runTest {
        whenever(lookup.getYears()).thenReturn(listOf("2024"))
        whenever(lookup.getMakes("2024")).thenReturn(listOf("Toyota"))
        whenever(lookup.getModels("2024", "Toyota")).thenReturn(listOf("Corolla"))
        val options = listOf(VehicleOption("unknown", "Unknown MPG"), VehicleOption("gas", "Automatic"))
        whenever(lookup.getVehicleOptions("2024", "Toyota", "Corolla")).thenReturn(options)
        val unknown = VehicleDetails(null, FuelType.REGULAR)
        val gas = VehicleDetails(32f, FuelType.PREMIUM)
        whenever(lookup.getVehicleDetails("unknown")).thenReturn(unknown)
        whenever(lookup.getVehicleDetails("gas")).thenReturn(gas)
        val initial = VehicleDraftState()
        val received = mutableListOf<VehicleDetails>()
        val draft = VehicleDraft(lookup, CoroutineScope(coroutineContext + StandardTestDispatcher(testScheduler)),
            initial, onShapeChanged = {}, onTrimDetails = { received.add(it) })

        draft.allowLookup()
        testScheduler.advanceUntilIdle()
        assertTrue(draft.state.value.lookupAllowed)
        assertEquals(listOf("2024"), draft.state.value.availableYears)
        draft.onYearSelected("2024")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(VEHICLE_NOT_LISTED, "Toyota"), draft.state.value.availableMakes)
        draft.onMakeSelected("Toyota")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(VEHICLE_NOT_LISTED, "Corolla"), draft.state.value.availableModels)
        draft.onModelSelected("Corolla")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(VehicleOption("NOT_LISTED", VEHICLE_NOT_LISTED)) + options,
            draft.state.value.availableTrims)
        assertEquals(listOf(VEHICLE_NOT_LISTED, "Unknown MPG", "Automatic"), draft.state.value.trimNames)

        draft.onTrimSelected("Unknown MPG")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(unknown), received)
        assertEquals(25f, draft.state.value.mpg, 0f)
        assertFalse(EconomyField.VEHICLE_MPG in draft.state.value.userSetFields)
        draft.onTrimSelected("Automatic")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(unknown, gas), received)
        assertEquals(32f, draft.state.value.mpg, 0f)
        assertTrue(EconomyField.VEHICLE_MPG in draft.state.value.userSetFields)
        assertEquals("2024", draft.state.value.year)
        assertEquals("Toyota", draft.state.value.make)
        assertEquals("Corolla", draft.state.value.model)
        assertEquals("Automatic", draft.state.value.trim)

        verify(lookup).getYears()
        verify(lookup).getMakes("2024")
        verify(lookup).getModels("2024", "Toyota")
        verify(lookup).getVehicleOptions("2024", "Toyota", "Corolla")
        verify(lookup).getVehicleDetails("unknown")
        verify(lookup).getVehicleDetails("gas")
        verifyNoMoreInteractions(lookup)
    }

    @Test
    fun `nothing is fetched without lookup permission even with cached options`() = runTest {
        val initial = VehicleDraftState(year = "2024", make = "Toyota", model = "Corolla",
            availableYears = listOf("2024"), availableTrims = listOf(VehicleOption("gas", "Automatic")))
        val draft = VehicleDraft(lookup, CoroutineScope(coroutineContext + StandardTestDispatcher(testScheduler)),
            initial, onShapeChanged = {})

        draft.fetchYears()
        draft.onYearSelected("2024")
        draft.onMakeSelected("Toyota")
        draft.onModelSelected("Corolla")
        draft.onTrimSelected("Automatic")
        draft.onMakeSelected(VEHICLE_NOT_LISTED)
        draft.onModelSelected(VEHICLE_NOT_LISTED)
        draft.onTrimSelected(VEHICLE_NOT_LISTED)
        draft.onManualMpgEntry()
        draft.updateEstimatedMpg(32f)
        draft.updateVehicleType(VehicleType.CAR)
        draft.updateVehicleType(VehicleType.BICYCLE)
        draft.updateVehicleClass(VehicleClass.SUV)
        testScheduler.advanceUntilIdle()

        verifyNoInteractions(lookup)
    }

    @Test
    fun `type change resets typed overrides and vehicle fields but retains the tariff`() = runTest {
        val initial = VehicleDraftState(
            profile = GasCar(mpg = 32.0, wear = WearProfile(tireSetCost = 900.0)),
            mpg = 32f, kwhPer100Mi = 42.0, whPerMile = 15.0, electricityPricePerKwh = .22,
            userSetFields = VehicleDraftState.fields,
        )
        var changes = 0
        val draft = VehicleDraft(lookup, CoroutineScope(coroutineContext + StandardTestDispatcher(testScheduler)),
            initial, onShapeChanged = { changes++ })

        draft.updateVehicleType(VehicleType.E_BIKE)
        testScheduler.advanceUntilIdle()

        assertEquals(VehicleType.E_BIKE.defaultProfile(), draft.state.value.profile)
        assertEquals(VehicleClass.E_BIKE, draft.state.value.vehicleClass)
        assertEquals(VehicleDraftState.fields - EconomyFieldGroups.vehicleFields, draft.state.value.userSetFields)
        assertEquals(setOf(EconomyField.ELECTRICITY_PRICE), draft.state.value.userSetFields)
        assertEquals(.22, draft.state.value.electricityPricePerKwh, 0.0)
        assertEquals(VehiclePresets.EBIKE_WH_PER_MILE, draft.state.value.whPerMile, 0.0)
        assertEquals(1, changes)
        verifyNoInteractions(lookup)
    }

    @Test
    fun `same type retains the draft and refetches years without a shape callback`() = runTest {
        whenever(lookup.getYears()).thenReturn(listOf("2024"))
        val initial = VehicleDraftState(profile = GasCar(mpg = 32.0), mpg = 32f,
            lookupAllowed = true, availableYears = listOf("2024"),
            userSetFields = setOf(EconomyField.VEHICLE_MPG))
        var changes = 0
        val draft = VehicleDraft(lookup, CoroutineScope(coroutineContext + StandardTestDispatcher(testScheduler)),
            initial, onShapeChanged = { changes++ })

        draft.updateVehicleType(VehicleType.CAR)
        testScheduler.advanceUntilIdle()

        assertEquals(initial, draft.state.value)
        assertEquals(0, changes)
        verify(lookup).getYears()
        verifyNoMoreInteractions(lookup)
    }

    @Test
    fun `profile applies identity only to car shaped profiles and MPG only when user set`() {
        VehicleType.entries.forEach { type ->
            val original = type.defaultProfile().withIdentity("old year", "old make", "old model")
            val state = VehicleDraftState(profile = original, year = "2024", make = "Toyota", model = "Corolla", mpg = 32f)
            val result = state.profile()
            assertEquals(if (original.hasCarIdentity) "2024" else "old year", result.year)
            assertEquals(if (original.hasCarIdentity) "Toyota" else "old make", result.make)
            assertEquals(if (original.hasCarIdentity) "Corolla" else "old model", result.model)
            assertNull(result.gasMpg)
            assertEquals(if (EconomyField.VEHICLE_MPG in EconomyFieldGroups.fieldsFor(original)) 32.0 else null,
                state.copy(userSetFields = setOf(EconomyField.VEHICLE_MPG)).profile().gasMpg)
        }
        val blank = VehicleDraftState(profile = GasCar(year = "2024", make = "Toyota", model = "Corolla"),
            year = " ", make = "", model = "\t").profile()
        assertNull(blank.year)
        assertNull(blank.make)
        assertNull(blank.model)
        assertEquals(28.0, VehicleDraftState(profile = GasCar(mpg = 28.0), mpg = 32f).profile().gasMpg)
    }

    @Test
    fun `profile applies only user set consumption and preserves typed costs`() {
        val wear = WearProfile(tireSetCost = 900.0)
        val fixed = FixedCosts(insuranceDeltaPerMonth = 25.0)
        val electric = VehicleDraftState(profile = ElectricCar(wear = wear, fixed = fixed), kwhPer100Mi = 27.0)
        assertNull((electric.profile() as ElectricCar).kwhPer100Mi)
        val edited = electric.copy(userSetFields = setOf(EconomyField.KWH_PER_100_MI)).profile() as ElectricCar
        assertEquals(27.0, edited.kwhPer100Mi)
        assertEquals(wear, edited.wear)
        assertEquals(fixed, edited.fixed)
        val bike = VehicleDraftState(profile = EBike(), whPerMile = 15.0)
        assertNull((bike.profile() as EBike).whPerMile)
        assertEquals(15.0, (bike.copy(userSetFields = setOf(EconomyField.WH_PER_MILE)).profile() as EBike).whPerMile)
    }
}
