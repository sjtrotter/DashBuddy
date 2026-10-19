package cloud.trotter.dashbuddy.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.dashbuddy.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VehicleEconomyPreferenceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `active profile owns car costs while gas and phone remain shared`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("prefs.preferences_pb") }))
        val vehicleSource = VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("garage.preferences_pb") }))
        val garage = GarageRepository(vehicleSource)
        val repo = AppPreferencesRepository(source, garage)
        val compact = garage.addVehicle("Compact", 1, VehicleProfile(vehicleClass = VehicleClass.COMPACT))
        repo.updateTireCost(700.0, 42_000.0)
        repo.updateEstimatedMpg(35f)
        repo.updatePhonePlan(100.0, 2, 50.0)
        repo.updateGasPrice(4.00f)
        val truck = garage.addVehicle("Truck", 2, VehicleProfile(vehicleClass = VehicleClass.TRUCK))
        assertEquals(VehicleClass.TRUCK.tireSetCost, repo.userEconomy.first().tireSetCost, 0.0)
        assertEquals(100.0, repo.userEconomy.first().phonePlanTotal, 0.0)
        assertEquals(4.00, repo.userEconomy.first().gasPricePerGallon, 0.0)
        assertTrue(garage.selectVehicle(compact.id))
        assertEquals(700.0, repo.userEconomy.first().tireSetCost, 0.0)
        assertEquals(35.0, repo.userEconomy.first().vehicleMpg, 0.0)
        assertEquals(42_000.0, repo.userEconomy.first().tireLifetimeMi, 0.0)
        assertTrue(garage.selectVehicle(truck.id))
        assertEquals(VehicleClass.TRUCK.tireSetCost, repo.userEconomy.first().tireSetCost, 0.0)
        assertEquals(700.0, source.tireSetCost.first()!!, 0.0)
        vehicleSource.save("invalid json", truck.id)
        // A failed vehicle read falls back to the legacy preference snapshot.
        assertEquals(700.0, repo.userEconomy.first().tireSetCost, 0.0)
        assertEquals(VehicleClass.SEDAN, repo.userEconomy.first().vehicleClass)
    }

    @Test fun `settings create an active car and retain several edits before garage setup`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("new-prefs.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("new-garage.preferences_pb") })))
        val repo = AppPreferencesRepository(source, garage)
        repo.updateVehicleClass(VehicleClass.TRUCK)
        repo.updateFuelType(FuelType.DIESEL)
        repo.updateEstimatedMpg(22f)
        repo.updateTireCost(1100.0, 35_000.0)
        repo.updateExpectedAnnualMi(18_000.0)
        val active = garage.garage.first().active!!
        assertEquals(1, garage.garage.first().vehicles.size)
        assertNull(active.name)
        assertEquals(VehicleClass.TRUCK, active.profile.vehicleClass)
        assertEquals(FuelType.DIESEL, active.profile.fuelType)
        assertEquals(22.0, active.profile.mpg)
        assertEquals(1100.0, active.profile.tireSetCost)
        assertEquals(18_000.0, active.profile.expectedAnnualMiles)
        assertEquals(1100.0, repo.userEconomy.first().tireSetCost, 0.0)
    }
}
