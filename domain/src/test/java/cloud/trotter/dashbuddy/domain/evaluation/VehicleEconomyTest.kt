package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class VehicleEconomyTest {
    private fun economy(profile: VehicleProfile, shared: SharedEconomy = SharedEconomy()): UserEconomy =
        UserEconomy.from(VehicleEconomyInputs.from(profile, shared), shared)

    @Test fun `the sedan default economy is unchanged to the cent`() {
        // Independent pre-1358 constants, including the production phone share, not the new presets.
        val before = 3.50 / 30 + 800.0 / 40_000 + 60.0 / 5_000 + 400.0 / 40_000 +
            150.0 / 30_000 + 500.0 / 12_000 + 28_000.0 / 200_000 + (80.0 * .30 * 12) / 10_000
        val after = economy(GasCar()).operatingCostPerMile
        assertEquals(37, (before * 100).roundToInt())
        assertEquals((before * 100).roundToInt(), (after * 100).roundToInt())
        assertEquals(before, after, 1e-12)
    }

    @Test fun `typed sedan overrides keep every existing term including phone allocation`() {
        val car = GasCar(mpg = 27.0, wear = WearProfile(tireSetCost = 0.0, oilCost = 70.0),
            fixed = FixedCosts(false, insuranceDeltaPerMonth = 25.0, registrationDeltaPerYear = 100.0, expectedAnnualMiles = 12_000.0))
        val shared = SharedEconomy(gasPricePerGallon = 4.0, phonePlanTotal = 120.0, phonePlanLines = 3, phoneBusinessPercent = 50.0)
        val expected = 4.0 / 27 + 70.0 / 5_000 + 400.0 / 40_000 + 150.0 / 30_000 + 500.0 / 12_000 +
            25.0 * 12 / 12_000 + 100.0 / 12_000 + (120.0 / 3 * .5 * 12) / 12_000
        assertEquals(expected, economy(car, shared).operatingCostPerMile, 1e-12)
        assertTrue(EconomyField.TIRE_COST in economy(car).userSetFields)
        assertFalse(EconomyField.TIRE_LIFETIME in economy(car).userSetFields)
    }

    @Test fun `an EV charges electricity`() {
        val ev = economy(ElectricCar())
        assertEquals(.045, ev.fuelCostPerMile, 1e-12)
        assertEquals(0.0, ev.oilCostPerMile, 0.0)
        assertEquals(.08, economy(ElectricCar(40.0), SharedEconomy(electricityPricePerKwh = .20)).fuelCostPerMile, 1e-12)
    }

    @Test fun `an e-bike charges battery wear and tiny energy`() {
        val bike = economy(EBike())
        assertEquals(.003, bike.fuelCostPerMile, 1e-12)
        assertEquals(500.0 / 15_000, bike.batteryCostPerMile, 1e-12)
        assertEquals(1_800.0 / 20_000, bike.depreciationCostPerMile, 1e-12)
        assertEquals(80.0 / 3_000 + 40.0 / 4_000 + 40.0 / 2_500 + 500.0 / 15_000 + 50.0 / 2_000,
            bike.vehicleInputs!!.wearPerMile, 1e-12)
    }

    @Test fun `a bicycle has no energy term`() {
        val bike = economy(Bicycle(), SharedEconomy(gasPricePerGallon = 100.0, electricityPricePerKwh = 100.0))
        assertEquals(0.0, bike.fuelCostPerMile, 0.0)
        assertTrue(bike.operatingCostPerMile > 0.0)
        assertEquals(0.0, bike.batteryCostPerMile, 0.0)
    }

    @Test fun `hybrid motorcycle and both scooters use their own estimates`() {
        assertEquals(3.5 / 45, economy(HybridCar()).fuelCostPerMile, 1e-12)
        assertEquals(60_000.0, economy(HybridCar()).brakesIntervalMi, 0.0)
        assertEquals(.01, economy(Motorcycle()).chainCostPerMile, 1e-12)
        assertEquals(3.5 / 90, economy(ScooterMoped()).fuelCostPerMile, 1e-12)
        assertEquals(.0045, economy(ScooterMoped(Powertrain.ELECTRIC)).fuelCostPerMile, 1e-12)
        assertEquals(.008, economy(ScooterMoped()).beltCostPerMile, 1e-12)
    }

    @Test fun `the active type changes the time profile`() {
        for ((profile, pace, pickup) in listOf(Triple(GasCar(), 2.5, 7.0), Triple(ElectricCar(), 2.5, 7.0),
            Triple(Motorcycle(), 2.3, 5.0), Triple(EBike(), 5.0, 4.0), Triple(Bicycle(), 6.0, 4.0))) {
            val raw = economy(profile)
            val resolved = EvaluationConfig(userEconomy = raw).forPlatform(Platform.DoorDash).userEconomy
            assertEquals(pace, resolved.effectiveAvgMinutesPerMile, 0.0)
            assertEquals(pickup, resolved.effectiveBasePickupMinutes, 0.0)
        }
        val override = economy(Bicycle(), SharedEconomy(avgMinutesPerMile = 3.0, userSetFields = setOf(EconomyField.AVG_MIN_PER_MILE)))
        assertEquals(3.0, override.effectiveAvgMinutesPerMile, 0.0)
    }

    @Test fun `vehicle-specific learned travel wins with a platform-only fallback`() {
        val legacy = LearnedTimeConstants(TimeConstantPair(2.0, 8.0), 40)
        val scoped = LearnedTimeConstants(TimeConstantPair(5.0, 4.0), 40)
        val base = EvaluationConfig(userEconomy = economy(EBike()).copy(activeVehicleId = "bike"),
            timeConstants = mapOf(Platform.DoorDash to legacy),
            vehicleTimeConstants = mapOf(VehicleTimeKey(Platform.DoorDash, "bike") to scoped))
        assertEquals(scoped, base.learnedTimeFor(Platform.DoorDash))
        assertEquals(5.0, base.forPlatform(Platform.DoorDash).userEconomy.effectiveAvgMinutesPerMile, 1e-12)
        assertEquals(legacy, base.copy(userEconomy = base.userEconomy.copy(activeVehicleId = "other")).learnedTimeFor(Platform.DoorDash))
        assertNull(base.learnedTimeFor(Platform.entries.first { it != Platform.DoorDash && it != Platform.Unknown }))
    }

    @Test fun `draft overrides preserve untouched nulls and commit explicit cost groups`() {
        val original = GasCar(make = "Honda", mpg = 31.0, wear = WearProfile(oilCost = 90.0))
        val draft = UserEconomy(tireSetCost = 720.0, tireLifetimeMi = 48_000.0,
            includeDepreciation = false, purchasePrice = 25_000.0, totalLifetimeMi = 180_000.0,
            insuranceDeltaPerMonth = 0.0,
            userSetFields = setOf(EconomyField.TIRE_COST, EconomyField.PURCHASE_PRICE, EconomyField.INSURANCE_DELTA))
        assertEquals(original.copy(wear = original.wear.copy(tireSetCost = 720.0, tireLifetimeMi = 48_000.0),
            fixed = FixedCosts(false, 25_000.0, 180_000.0, 0.0)), EconomyFieldGroups.updateProfile(original, draft))
        assertEquals(original, EconomyFieldGroups.updateProfile(original, UserEconomy(userSetFields = setOf(EconomyField.PHONE_PLAN_TOTAL))))
    }

    @Test fun `every EconomyField belongs to exactly one group and inactive writes are ignored`() {
        val fields = EconomyFieldGroups.entries.flatMap { it.fields }
        assertEquals(EconomyField.entries.toSet(), fields.toSet())
        assertEquals(EconomyField.entries.size, fields.size)
        val bike = Bicycle()
        assertEquals(bike, EconomyFieldGroups.updateProfile(bike, UserEconomy(oilCost = 100.0,
            userSetFields = setOf(EconomyField.OIL_COST))))
        val draft = UserEconomy(chainCost = 70.0, chainLifeMi = 4_000.0, batteryCost = 600.0, batteryLifeMi = 20_000.0,
            userSetFields = setOf(EconomyField.CHAIN_COST, EconomyField.BATTERY_COST))
        val result = EconomyFieldGroups.updateProfile(EBike(), draft)
        assertEquals(WearProfile(chainCost = 70.0, chainLifeMi = 4_000.0, batteryCost = 600.0, batteryLifeMi = 20_000.0), result.wear)
    }

    @Test fun `a zero mpg falls back to the class default`() {
        for (mpg in listOf(0.0, -1.0)) {
            val result = economy(GasCar(GasCarClass.COMPACT, mpg))
            assertEquals(32.0, result.vehicleMpg, 0.0)
            assertFalse(EconomyField.VEHICLE_MPG in result.userSetFields)
        }
    }
}
