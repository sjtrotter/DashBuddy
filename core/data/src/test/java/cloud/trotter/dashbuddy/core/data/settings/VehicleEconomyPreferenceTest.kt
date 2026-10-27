package cloud.trotter.dashbuddy.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.dashbuddy.core.datastore.settings.StoredPreference
import cloud.trotter.dashbuddy.core.datastore.settings.StoredGasPrice
import cloud.trotter.dashbuddy.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.EconomyDraft
import cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import timber.log.Timber
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VehicleEconomyPreferenceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `active typed profiles feed one economy with shared tariffs and vehicle travel`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("typed-prefs.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("typed-garage.preferences_pb") })))
        val repo = AppPreferencesRepository(source, garage)
        val sedan = garage.addVehicle("Sedan", 1, GasCar())
        val before = 3.5 / 30 + 800.0 / 40_000 + 60.0 / 5_000 + 400.0 / 40_000 + 150.0 / 30_000 +
            500.0 / 12_000 + 28_000.0 / 200_000 + 80 * .30 * 12 / 10_000
        assertEquals(before, repo.userEconomy.first().operatingCostPerMile, 1e-12)
        garage.addVehicle("EV", 2, ElectricCar())
        repo.updateElectricityPrice(.20)
        assertEquals(.06, repo.userEconomy.first().fuelCostPerMile, 1e-12)
        assertEquals(0.0, repo.userEconomy.first().oilCostPerMile, 0.0)
        val bike = garage.addVehicle("E-bike", 3, EBike())
        assertEquals(.004, repo.userEconomy.first().fuelCostPerMile, 1e-12)
        assertEquals(500.0 / 15_000, repo.userEconomy.first().batteryCostPerMile, 1e-12)
        assertEquals(5.0, repo.userEconomy.first().effectiveAvgMinutesPerMile, 0.0)
        assertEquals(bike.id, repo.userEconomy.first().activeVehicleId)
        garage.addVehicle("Bicycle", 4, Bicycle())
        assertEquals(0.0, repo.userEconomy.first().fuelCostPerMile, 0.0)
        assertEquals(6.0, repo.userEconomy.first().effectiveAvgMinutesPerMile, 0.0)
        garage.selectVehicle(sedan.id)
        assertEquals(before, repo.userEconomy.first().operatingCostPerMile, 1e-12)
        assertEquals(2.5, repo.userEconomy.first().effectiveAvgMinutesPerMile, 0.0)
    }

    @Test fun `unreadable garage preserves cost-only legacy preference fallback`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("cost-only-prefs.preferences_pb") }))
        val vehicleSource = VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("cost-only-garage.preferences_pb") }))
        source.updateTireCost(0.0, 45_000.0)
        vehicleSource.save("unreadable", "old")
        val repo = AppPreferencesRepository(source, GarageRepository(vehicleSource))
        val economy = repo.userEconomy.first()
        assertEquals(0.0, economy.tireSetCost, 0.0)
        assertEquals(45_000.0, economy.tireLifetimeMi, 0.0)
        assertTrue(economy.vehicleProfile is GasCar)
        assertNull(economy.activeVehicleId)
        assertEquals("unreadable", vehicleSource.stored.first().json)
    }

    @Test fun `legacy mirrors do not write either garage profile after a switch`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("mirror-prefs.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("mirror-garage.preferences_pb") })))
        val repo = AppPreferencesRepository(source, garage)
        val first = garage.addVehicle("First", 1L, LegacyProfile(make = "Honda").toTyped())
        val second = garage.addVehicle("Second", 2L, LegacyProfile(make = "Toyota").toTyped())
        garage.selectVehicle(first.id)
        garage.selectVehicle(second.id)
        val before = garage.garage.first()
        val economy = EconomyDraft(wear = WearProfile(tireSetCost = 720.0, tireLifetimeMi = 48_000.0),
            shared = SharedEconomy(phonePlanTotal = 80.0, phonePlanLines = 2, phoneBusinessPercent = 50.0,
                avgMinutesPerMile = 3.0),
            userSetFields = setOf(EconomyField.TIRE_COST, EconomyField.PHONE_PLAN_TOTAL, EconomyField.AVG_MIN_PER_MILE))

        repo.mirrorFuelType(FuelType.PREMIUM)
        repo.mirrorVehicleClass(VehicleClass.SUV)
        repo.mirrorUserSetEconomy(economy)
        repo.mirrorEconomySettings("2021", "Honda", "Civic", "EX", 28f, false, 3.75f)

        assertEquals(before, garage.garage.first())
        assertEquals(FuelType.PREMIUM.name, source.fuelType.first())
        assertEquals(VehicleClass.SUV.name, source.vehicleClass.first())
        assertEquals("2021", source.vehicleYear.first())
        assertEquals("Honda", source.vehicleMake.first())
        assertEquals("Civic", source.vehicleModel.first())
        assertEquals("EX", source.vehicleTrim.first())
        assertEquals(28f, source.estimatedMpg.first())
        assertEquals(false, source.isGasPriceAuto.first())
        assertEquals(3.75f, source.gasPrice.first())
        assertEquals(720.0, source.tireSetCost.first())
        assertNull(source.tireLifetimeMi.first())
        assertEquals(80.0, source.phonePlanTotal.first())
        assertNull(source.phonePlanLines.first())
        assertNull(source.phoneBusinessPercent.first())
        assertEquals(3.0, source.avgMinPerMile.first())
        assertNull(source.oilCost.first())
        assertNull(source.basePickupMin.first())
        assertTrue(source.userSetEconomyFields.first().containsAll(setOf(
            EconomyField.TIRE_COST.name,
            EconomyField.PHONE_PLAN_TOTAL.name, EconomyField.AVG_MIN_PER_MILE.name,
        )))
    }

    @Test fun `shared economy drafts do not create a garage vehicle`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("shared-prefs.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("shared-garage.preferences_pb") })))
        AppPreferencesRepository(source, garage).mirrorUserSetEconomy(EconomyDraft(
            shared = SharedEconomy(avgMinutesPerMile = 3.0), userSetFields = setOf(EconomyField.AVG_MIN_PER_MILE),
        ))
        assertTrue(garage.garage.first().vehicles.isEmpty())
        assertEquals(3.0, source.avgMinPerMile.first())
    }
    @Test fun `phone scalar writes preserve siblings and mark only the edited field`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("phone.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("phone-garage.preferences_pb") })))
        val repo = AppPreferencesRepository(source, garage)
        repo.updatePhoneTotal(125.0)
        assertEquals(setOf(EconomyField.PHONE_PLAN_TOTAL.name), source.userSetEconomyFields.first())
        assertNull(source.phonePlanLines.first())
        assertNull(source.phoneBusinessPercent.first())
        repo.updatePhoneLines(3)
        assertEquals(125.0, source.phonePlanTotal.first())
        assertEquals(3, source.phonePlanLines.first())
        assertTrue(garage.garage.first().vehicles.isEmpty())
    }
    @Test fun `shared reset leaves every vehicle and legacy vehicle key intact`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("reset.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("reset-garage.preferences_pb") })))
        val repo = AppPreferencesRepository(source, garage)
        source.updateOilCost(72.0, 5000.25)
        garage.addVehicle("EV", 1, ElectricCar(kwhPer100Mi = 42.0, wear = WearProfile(tireSetCost = 999.0)))
        val before = garage.garage.first()
        repo.updatePhoneTotal(125.0)
        repo.updateElectricityPrice(.25)
        repo.resetSharedEconomyDefaults()
        assertEquals(before, garage.garage.first())
        assertEquals(72.0, source.oilCost.first())
        assertEquals(5000.25, source.oilIntervalMi.first())
        assertNull(source.phonePlanTotal.first())
        assertNull(source.electricityPricePerKwh.first())
        assertEquals(setOf(EconomyField.OIL_COST.name, EconomyField.OIL_INTERVAL.name), source.userSetEconomyFields.first())
    }


    @Test fun `shared undo restores raw absence or override and leaves phone siblings intact`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("undo.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("undo-garage.preferences_pb") })))
        val repo = AppPreferencesRepository(source, garage)
        repo.updatePhoneTotal(125.0)
        repo.updatePhoneShare(45.0)
        val absent = repo.updatePhoneLines(3)
        assertNull(absent.value)
        assertFalse(absent.userSet)
        repo.restorePhoneLines(absent, StoredPreference(3, true))
        assertNull(source.phonePlanLines.first())
        assertFalse(EconomyField.PHONE_PLAN_LINES.name in source.userSetEconomyFields.first())
        repo.updatePhoneLines(2)
        val previous = repo.updatePhoneLines(3)
        assertEquals(2, previous.value)
        assertTrue(previous.userSet)
        repo.restorePhoneLines(previous, StoredPreference(3, true))
        assertEquals(2, source.phonePlanLines.first())
        assertEquals(125.0, source.phonePlanTotal.first())
        assertEquals(45.0, source.phoneBusinessPercent.first())
        assertTrue(EconomyField.PHONE_PLAN_LINES.name in source.userSetEconomyFields.first())
        // A raw stored value can exist without its user-set marker.
        source.restorePhoneLines(StoredPreference(2, false), StoredPreference(2, true))
        val unmarked = repo.updatePhoneLines(3)
        assertEquals(2, unmarked.value)
        assertFalse(unmarked.userSet)
        repo.restorePhoneLines(unmarked, StoredPreference(3, true))
        assertEquals(2, source.phonePlanLines.first())
        assertFalse(EconomyField.PHONE_PLAN_LINES.name in source.userSetEconomyFields.first())
        assertEquals(125.0, source.phonePlanTotal.first())
        assertEquals(45.0, source.phoneBusinessPercent.first())
        assertTrue(garage.garage.first().vehicles.isEmpty())
    }

    @Test fun `tariff time and gas undo restore all values and markers and reject changed auto`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("other-undo.preferences_pb") }))
        val electricity = source.updateElectricityPrice(.25)
        source.restoreElectricityPrice(electricity, StoredPreference(.25, true))
        assertNull(source.electricityPricePerKwh.first())
        val time = source.setTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, 4.0)
        source.restoreTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, time, StoredPreference(4.0, true))
        assertNull(source.avgMinPerMile.first())
        assertTrue(source.userSetEconomyFields.first().isEmpty())
        source.updateGasPriceAuto(3.25f)
        val gas = source.updateGasPriceManual(4.0f)
        assertFalse(source.isGasPriceAuto.first())
        val expected = StoredGasPrice(StoredPreference(4.0f, false), StoredPreference(false, false))
        assertTrue(source.restoreGasPriceManual(gas, expected))
        assertEquals(3.25f, source.gasPrice.first())
        assertTrue(source.isGasPriceAuto.first())
        val next = source.updateGasPriceManual(4.0f)
        source.setGasPriceAuto(true)
        assertFalse(source.restoreGasPriceManual(next, expected))
        assertEquals(4.0f, source.gasPrice.first())
        assertTrue(source.isGasPriceAuto.first())
        val auto = source.setGasPriceAuto(false)
        source.restoreGasPriceAuto(auto, StoredPreference(false, false))
        assertTrue(source.isGasPriceAuto.first())
        assertEquals(4.0f, source.gasPrice.first())
    }

}
