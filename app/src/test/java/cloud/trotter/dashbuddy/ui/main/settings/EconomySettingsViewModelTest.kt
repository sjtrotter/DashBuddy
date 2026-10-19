package cloud.trotter.dashbuddy.ui.main.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.dashbuddy.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class EconomySettingsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `vehicle heading follows active selection and retirement`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val car = Vehicle("car", "Car", 1)
        val bicycle = Vehicle("bicycle", "Bicycle", 2, Bicycle())
        val updates = MutableStateFlow(Garage(listOf(car, bicycle), car.id, true))
        val garage: GarageRepository = mock()
        whenever(garage.garage).thenReturn(updates)
        val preferences: AppPreferencesRepository = mock()
        whenever(preferences.userEconomy).thenReturn(flowOf(cloud.trotter.dashbuddy.domain.evaluation.UserEconomy()))
        whenever(preferences.fuelType).thenReturn(flowOf(FuelType.REGULAR))
        val strategy: StrategyRepository = mock()
        whenever(strategy.evaluationConfig).thenReturn(MutableStateFlow(null))
        val vm = EconomySettingsViewModel(preferences, strategy, garage)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.activeVehicle.collect {} }
        runCurrent()
        assertEquals(car, vm.activeVehicle.value)
        updates.value = updates.value.copy(activeId = bicycle.id)
        runCurrent()
        assertEquals(bicycle, vm.activeVehicle.value)
        updates.value = Garage(listOf(car, bicycle.copy(retiredAt = 3)), null, true)
        runCurrent()
        assertEquals(null, vm.activeVehicle.value)
    }

    @Test
    fun `the economy editor writes the active vehicle's profile`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmp.newFile("vehicles.preferences_pb") },
        )))
        val preferences = AppPreferencesRepository(AppPreferencesDataSource(PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmp.newFile("app.preferences_pb") },
        )), garage)
        val inactive = garage.addVehicle("Old car", 1L, LegacyProfile(vehicleClass = VehicleClass.COMPACT).toTyped())
        val active = garage.addVehicle("Current car", 2L, LegacyProfile(vehicleClass = VehicleClass.SEDAN).toTyped())
        val strategy: StrategyRepository = mock()
        whenever(strategy.evaluationConfig).thenReturn(MutableStateFlow(null))
        val viewModel = EconomySettingsViewModel(preferences, strategy, garage)

        viewModel.setVehicleClass(VehicleClass.SUV)
        viewModel.setMpg(27.5)
        viewModel.setFuelType(FuelType.DIESEL)
        viewModel.setTires(720.0, 48_000.0)
        viewModel.setOil(85.0, 7_500.0)
        viewModel.setBrakes(600.0, 50_000.0)
        viewModel.setFluids(125.0, 30_000.0)
        viewModel.setMiscRepairs(800.0, 12_000.0)
        viewModel.setDepreciation(true, 28_000.0, 180_000.0)
        viewModel.setInsurance(75.0)
        viewModel.setRegistration(120.0)
        viewModel.setExpectedAnnualMiles(15_000.0)
        viewModel.setPhone(80.0, 2, 50.0)
        advanceUntilIdle()

        val vehicles = garage.garage.first().vehicles
        val saved = vehicles.first { it.id == active.id }.profile
        assertEquals(VehicleClass.SUV, saved.legacyClass)
        assertEquals(27.5, saved.gasMpg)
        assertEquals(FuelType.DIESEL, saved.gasFuel)
        assertEquals(720.0, saved.wear.tireSetCost)
        assertEquals(48_000.0, saved.wear.tireLifetimeMi)
        assertEquals(85.0, saved.wear.oilCost)
        assertEquals(7_500.0, saved.wear.oilIntervalMi)
        assertEquals(600.0, saved.wear.brakesCost)
        assertEquals(50_000.0, saved.wear.brakesIntervalMi)
        assertEquals(125.0, saved.wear.fluidsCost)
        assertEquals(30_000.0, saved.wear.fluidsIntervalMi)
        assertEquals(800.0, saved.wear.miscYearly)
        assertEquals(12_000.0, saved.wear.miscYearlyMi)
        assertEquals(true, saved.fixed.includeDepreciation)
        assertEquals(28_000.0, saved.fixed.purchasePrice)
        assertEquals(180_000.0, saved.fixed.totalLifetimeMi)
        assertEquals(75.0, saved.fixed.insuranceDeltaPerMonth)
        assertEquals(120.0, saved.fixed.registrationDeltaPerYear)
        assertEquals(15_000.0, saved.fixed.expectedAnnualMiles)
        assertEquals(inactive.profile, vehicles.first { it.id == inactive.id }.profile)
        assertEquals(80.0, preferences.userEconomy.first().phonePlanTotal, 0.0)
    }
}
