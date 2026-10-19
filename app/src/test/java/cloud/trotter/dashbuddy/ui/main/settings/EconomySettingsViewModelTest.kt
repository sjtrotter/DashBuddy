package cloud.trotter.dashbuddy.ui.main.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.dashbuddy.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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
        val inactive = garage.addVehicle("Old car", 1L, VehicleProfile(vehicleClass = VehicleClass.COMPACT))
        val active = garage.addVehicle("Current car", 2L, VehicleProfile(vehicleClass = VehicleClass.SEDAN))
        val strategy: StrategyRepository = mock()
        whenever(strategy.evaluationConfig).thenReturn(MutableStateFlow(null))
        val viewModel = EconomySettingsViewModel(preferences, strategy)

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
        assertEquals(VehicleClass.SUV, saved.vehicleClass)
        assertEquals(27.5, saved.mpg)
        assertEquals(FuelType.DIESEL, saved.fuelType)
        assertEquals(720.0, saved.tireSetCost)
        assertEquals(48_000.0, saved.tireLifetimeMi)
        assertEquals(85.0, saved.oilCost)
        assertEquals(7_500.0, saved.oilIntervalMi)
        assertEquals(600.0, saved.brakesCost)
        assertEquals(50_000.0, saved.brakesIntervalMi)
        assertEquals(125.0, saved.fluidsCost)
        assertEquals(30_000.0, saved.fluidsIntervalMi)
        assertEquals(800.0, saved.miscYearly)
        assertEquals(12_000.0, saved.miscYearlyMi)
        assertEquals(true, saved.includeDepreciation)
        assertEquals(28_000.0, saved.purchasePrice)
        assertEquals(180_000.0, saved.totalLifetimeMi)
        assertEquals(75.0, saved.insuranceDeltaPerMonth)
        assertEquals(120.0, saved.registrationDeltaPerYear)
        assertEquals(15_000.0, saved.expectedAnnualMiles)
        assertEquals(inactive.profile, vehicles.first { it.id == inactive.id }.profile)
        assertEquals(80.0, preferences.userEconomy.first().phonePlanTotal, 0.0)
    }
}
