package cloud.trotter.dashbuddy.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.dashbuddy.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
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

    @Test fun `paired repository writes mirror only non-null halves and mark only edited fields`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("partial-prefs.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("partial-garage.preferences_pb") })))
        val repo = AppPreferencesRepository(source, garage)
        garage.addVehicle("Car", 1L, GasCar(wear = WearProfile(oilIntervalMi = 5000.25)))
        val writes = listOf<suspend (Double?, Double?) -> Unit>(
            repo::updateTireCost, repo::updateOilCost, repo::updateBrakesCost,
            repo::updateFluidsCost, repo::updateMiscMaintenance,
        )
        val costs = listOf(source.tireSetCost, source.oilCost, source.brakesCost, source.fluidsCost, source.miscYearly)
        val intervals = listOf(source.tireLifetimeMi, source.oilIntervalMi, source.brakesIntervalMi,
            source.fluidsIntervalMi, source.miscYearlyMi)
        val costFields = listOf(EconomyField.TIRE_COST, EconomyField.OIL_COST, EconomyField.BRAKES_COST,
            EconomyField.FLUIDS_COST, EconomyField.MISC_YEARLY)
        val intervalFields = listOf(EconomyField.TIRE_LIFETIME, EconomyField.OIL_INTERVAL, EconomyField.BRAKES_INTERVAL,
            EconomyField.FLUIDS_INTERVAL, EconomyField.MISC_YEARLY_MI)
        for (index in writes.indices) {
            writes[index](65.0, null)
            assertEquals(65.0, costs[index].first())
            assertNull(intervals[index].first())
            assertTrue(costFields[index].name in source.userSetEconomyFields.first())
            assertFalse(intervalFields[index].name in source.userSetEconomyFields.first())
        }
        assertEquals(5000.25, garage.garage.first().active!!.profile.wear.oilIntervalMi)
        for (index in writes.indices) {
            writes[index](null, 5000.25)
            writes[index](70.0, null)
            assertEquals(5000.25, intervals[index].first())
            writes[index](null, 6000.0)
            assertEquals(70.0, costs[index].first())
            assertEquals(6000.0, intervals[index].first())
            assertTrue(intervalFields[index].name in source.userSetEconomyFields.first())
        }
    }

    @Test fun `depreciation mirrors keep the switch price and lifetime independent`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("depreciation-prefs.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("depreciation-garage.preferences_pb") })))
        val repo = AppPreferencesRepository(source, garage)
        garage.addVehicle("Car", 1L, GasCar(fixed = FixedCosts(purchasePrice = 10000.25, totalLifetimeMi = 200000.25)))
        repo.updateDepreciationInclude(false)
        assertNull(source.purchasePrice.first())
        assertNull(source.totalLifetimeMi.first())
        assertEquals(setOf(EconomyField.INCLUDE_DEPRECIATION.name), source.userSetEconomyFields.first())
        repo.updateDepreciationValues(12000.0, null)
        repo.updateDepreciationInclude(true)
        assertEquals(12000.0, source.purchasePrice.first())
        assertNull(source.totalLifetimeMi.first())
        assertEquals(200000.25, garage.garage.first().active!!.profile.fixed.totalLifetimeMi)
        repo.updateDepreciationValues(null, 180000.0)
        assertEquals(true, source.includeDepreciation.first())
        assertEquals(12000.0, source.purchasePrice.first())
        assertEquals(180000.0, source.totalLifetimeMi.first())
    }

    @Test fun `active profile owns car costs while gas and phone remain shared`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("prefs.preferences_pb") }))
        val vehicleSource = VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("garage.preferences_pb") }))
        val garage = GarageRepository(vehicleSource)
        val repo = AppPreferencesRepository(source, garage)
        val compact = garage.addVehicle("Compact", 1, LegacyProfile(vehicleClass = VehicleClass.COMPACT).toTyped())
        repo.updateTireCost(700.0, 42_000.0)
        repo.updateEstimatedMpg(35f)
        repo.updatePhonePlan(100.0, 2, 50.0)
        repo.updateGasPrice(4.00f)
        val truck = garage.addVehicle("Truck", 2, LegacyProfile(vehicleClass = VehicleClass.TRUCK).toTyped())
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

    @Test fun `reset clears electric consumption overrides and their user-set marker`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("reset-prefs.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("reset-garage.preferences_pb") })))
        val repo = AppPreferencesRepository(source, garage)
        val vehicle = garage.addVehicle("EV", 1, ElectricCar(kwhPer100Mi = 42.0))
        repo.updateElectricityPrice(.25)
        assertEquals(42.0, repo.userEconomy.first().kwhPer100Mi, 0.0)
        assertTrue(EconomyField.KWH_PER_100_MI in repo.userEconomy.first().userSetFields)

        repo.resetEconomyDefaults()

        val economy = repo.userEconomy.first()
        assertEquals(VehiclePresets.EV_KWH_PER_100_MI, economy.kwhPer100Mi, 0.0)
        assertFalse(EconomyField.KWH_PER_100_MI in economy.userSetFields)
        assertFalse(EconomyField.ELECTRICITY_PRICE in economy.userSetFields)
        assertEquals(cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy.DEFAULT_ELECTRICITY_PRICE_PER_KWH,
            economy.electricityPricePerKwh, 0.0)
        val active = garage.garage.first().active!!
        assertEquals(vehicle.id, active.id)
        assertNull((active.profile as ElectricCar).kwhPer100Mi)
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
        assertEquals(VehicleClass.TRUCK, active.profile.legacyClass)
        assertEquals(FuelType.DIESEL, active.profile.gasFuel)
        assertEquals(22.0, active.profile.gasMpg)
        assertEquals(1100.0, active.profile.wear.tireSetCost)
        assertEquals(18_000.0, active.profile.fixed.expectedAnnualMiles)
        assertEquals(1100.0, repo.userEconomy.first().tireSetCost, 0.0)
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
        val economy = UserEconomy(tireSetCost = 720.0, tireLifetimeMi = 48_000.0,
            phonePlanTotal = 80.0, phonePlanLines = 2, phoneBusinessPercent = 50.0,
            avgMinutesPerMile = 3.0,
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
        assertEquals(2, source.phonePlanLines.first())
        assertEquals(50.0, source.phoneBusinessPercent.first())
        assertEquals(3.0, source.avgMinPerMile.first())
        assertNull(source.oilCost.first())
        assertNull(source.basePickupMin.first())
        assertTrue(source.userSetEconomyFields.first().containsAll(setOf(
            EconomyField.TIRE_COST.name,
            EconomyField.PHONE_PLAN_TOTAL.name, EconomyField.PHONE_PLAN_LINES.name,
            EconomyField.PHONE_BUSINESS_PERCENT.name, EconomyField.AVG_MIN_PER_MILE.name,
        )))
    }

    @Test fun `shared economy drafts do not create a garage vehicle`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("shared-prefs.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("shared-garage.preferences_pb") })))
        AppPreferencesRepository(source, garage).persistUserSetEconomy(UserEconomy(
            avgMinutesPerMile = 3.0, userSetFields = setOf(EconomyField.AVG_MIN_PER_MILE),
        ))
        assertTrue(garage.garage.first().vehicles.isEmpty())
        assertEquals(3.0, source.avgMinPerMile.first())
    }

    @Test fun `an unreadable garage keeps the preference write`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("unreadable-prefs.preferences_pb") }))
        val vehicleSource = VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("unreadable-garage.preferences_pb") }))
        vehicleSource.save("unreadable private vehicle data", "old-id")
        val before = vehicleSource.stored.first()
        val repo = AppPreferencesRepository(source, GarageRepository(vehicleSource))

        repo.updateVehicleClass(VehicleClass.COMPACT)
        repo.updateFuelType(FuelType.PREMIUM)
        repo.updateEstimatedMpg(34f)
        repo.updateTireCost(720.0, 48_000.0)
        repo.persistUserSetEconomy(UserEconomy(oilCost = 85.0, oilIntervalMi = 7_500.0,
            userSetFields = setOf(EconomyField.OIL_COST)))
        assertEquals(VehicleClass.COMPACT.name, source.vehicleClass.first())
        assertEquals(FuelType.PREMIUM.name, source.fuelType.first())
        assertEquals(34f, source.estimatedMpg.first())
        assertEquals(720.0, source.tireSetCost.first())
        assertEquals(48_000.0, source.tireLifetimeMi.first())
        assertEquals(85.0, source.oilCost.first())
        assertNull(source.oilIntervalMi.first())
        assertEquals(34.0, repo.userEconomy.first().vehicleMpg, 0.0)
        assertEquals(720.0, repo.userEconomy.first().tireSetCost, 0.0)
        assertEquals(before, vehicleSource.stored.first())
    }

    @Test fun `garage write failures warn once with only the exception class and cancellation propagates`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("failure-prefs.preferences_pb") }))
        val garage: GarageRepository = mock()
        whenever(garage.garage).thenReturn(flowOf(Garage(emptyList(), null, true)))
        whenever(garage.updateOrCreateActiveProfile(any(), any()))
            .thenThrow(IllegalStateException("private vehicle data"))
        val repo = AppPreferencesRepository(source, garage)
        val warnings = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == 5 && tag == "Garage") {
                    assertNull(t)
                    warnings += message
                }
            }
        }
        Timber.plant(tree)
        try {
            repo.updateEstimatedMpg(30f)
            repo.updateTireCost(720.0, 48_000.0)
            assertEquals(listOf("IllegalStateException"), warnings)
            assertEquals(30f, source.estimatedMpg.first())
            val cancelled = CancellationException("cancelled")
            whenever(garage.updateOrCreateActiveProfile(any(), any())).thenThrow(cancelled)
            assertSame(cancelled, runCatching { repo.updateEstimatedMpg(40f) }.exceptionOrNull())
            assertEquals(30f, source.estimatedMpg.first())
            assertEquals(listOf("IllegalStateException"), warnings)
        } finally {
            Timber.uproot(tree)
        }
    }
}
