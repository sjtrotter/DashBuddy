package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class VehicleEconomyTest {
    private fun economy(profile: VehicleProfile, shared: SharedEconomy = SharedEconomy()): UserEconomy =
        UserEconomy.from(VehicleEconomyInputs.from(profile, shared), shared)

    @Test fun `resolved consumption matches the economy for every profile type`() {
        val profiles = VehicleType.entries.map { it.defaultProfile() } + listOf(
            ScooterMoped(Powertrain.ELECTRIC),
            GasCar(mpg = 32.0), HybridCar(mpg = 50.0), ElectricCar(kwhPer100Mi = 27.0),
            Motorcycle(mpg = 60.0), ScooterMoped(mpg = 110.0, kwhPer100Mi = 8.0),
            ScooterMoped(Powertrain.ELECTRIC, mpg = 110.0, kwhPer100Mi = 8.0), EBike(whPerMile = 15.0),
            GasCar(mpg = 0.0), HybridCar(mpg = -1.0),
        )
        profiles.forEach { profile ->
            val resolved = profile.resolvedConsumption()
            listOf(SharedEconomy(), SharedEconomy(gasPricePerGallon = 5.0, electricityPricePerKwh = .30))
                .forEach { shared ->
                    val economy = economy(profile, shared)
                    assertEquals(profile.toString(), economy.vehicleMpg, resolved.mpg, 0.0)
                    assertEquals(profile.toString(), economy.kwhPer100Mi, resolved.kwhPer100Mi, 0.0)
                    assertEquals(profile.toString(), economy.whPerMile, resolved.whPerMile, 0.0)
                }
        }
    }

    @Test fun `car class mappings are inverse and non-car legacy classes have no gas class`() {
        GasCarClass.entries.forEach { assertEquals(it, it.legacy.gasClass) }
        VehicleClass.entries.forEach { cls ->
            when (cls) {
                VehicleClass.EV, VehicleClass.MOTORCYCLE, VehicleClass.E_BIKE -> assertNull(cls.gasClass)
                else -> assertEquals(cls, cls.gasClass!!.legacy)
            }
        }
    }

    @Test fun `default consumption clears each override without changing identity or costs`() {
        val profiles = listOf(GasCar(mpg = 40.0), HybridCar(mpg = 50.0), ElectricCar(kwhPer100Mi = 42.0),
            Motorcycle(mpg = 60.0), ScooterMoped(mpg = 110.0, kwhPer100Mi = 8.0),
            ScooterMoped(Powertrain.ELECTRIC, mpg = 110.0, kwhPer100Mi = 8.0), EBike(whPerMile = 35.0), Bicycle())
        profiles.forEach { original ->
            val profile = original.withIdentity("2024", "Maker", "Model")
                .withCosts(WearProfile(tireSetCost = 123.0), FixedCosts(purchasePrice = 456.0))
            val reset = profile.withDefaultConsumption()
            assertEquals(profile.vehicleType, reset.vehicleType)
            assertEquals(profile.year, reset.year)
            assertEquals(profile.make, reset.make)
            assertEquals(profile.model, reset.model)
            assertEquals(profile.wear, reset.wear)
            assertEquals(profile.fixed, reset.fixed)
            assertNull(reset.gasMpg)
            when (reset) {
                is ElectricCar -> assertNull(reset.kwhPer100Mi)
                is ScooterMoped -> { assertNull(reset.mpg); assertNull(reset.kwhPer100Mi) }
                is EBike -> assertNull(reset.whPerMile)
                else -> Unit
            }
            assertTrue(economy(reset).userSetFields.intersect(setOf(
                EconomyField.VEHICLE_MPG, EconomyField.KWH_PER_100_MI, EconomyField.WH_PER_MILE)).isEmpty())
        }
    }

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

    @Test fun `draft overrides preserve untouched nulls and commit explicit cost groups`() {
        val original = GasCar(make = "Honda", mpg = 31.0, wear = WearProfile(oilCost = 90.0))
        val draft = UserEconomy(tireSetCost = 720.0, tireLifetimeMi = 48_000.0,
            includeDepreciation = false, purchasePrice = 25_000.0, totalLifetimeMi = 180_000.0,
            insuranceDeltaPerMonth = 0.0,
            userSetFields = setOf(EconomyField.TIRE_COST, EconomyField.PURCHASE_PRICE, EconomyField.INSURANCE_DELTA))
        assertEquals(original.copy(wear = original.wear.copy(tireSetCost = 720.0),
            fixed = original.fixed.copy(purchasePrice = 25_000.0, insuranceDeltaPerMonth = 0.0)), EconomyFieldGroups.updateProfile(original, draft))
        assertEquals(original.copy(wear = original.wear.copy(tireSetCost = 720.0, tireLifetimeMi = 48_000.0),
            fixed = original.fixed.copy(purchasePrice = 25_000.0, insuranceDeltaPerMonth = 0.0)),
            EconomyFieldGroups.updateProfile(original, draft.copy(userSetFields = draft.userSetFields + EconomyField.TIRE_LIFETIME)))
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
        assertEquals(WearProfile(chainCost = 70.0, batteryCost = 600.0), result.wear)
    }

    @Test fun `a zero mpg falls back to the class default`() {
        for (mpg in listOf(0.0, -1.0)) {
            val result = economy(GasCar(GasCarClass.COMPACT, mpg))
            assertEquals(32.0, result.vehicleMpg, 0.0)
            assertFalse(EconomyField.VEHICLE_MPG in result.userSetFields)
        }
    }
    @Test fun `annual miles default belongs to the vehicle rather than a legacy shared mirror`() {
        val shared = SharedEconomy(expectedAnnualMiles = 99000.0)
        val profile = GasCar(fixed = FixedCosts(insuranceDeltaPerMonth = 100.0))
        val resolved = economy(profile, shared)
        assertEquals(UserEconomy.DEFAULT_ANNUAL_MI, resolved.expectedAnnualMiles, 0.0)
        assertEquals(1200.0 / UserEconomy.DEFAULT_ANNUAL_MI, resolved.insuranceCostPerMile, 1e-12)
        val explicit = economy(profile.copy(fixed = profile.fixed.copy(expectedAnnualMiles = 12000.0)), shared)
        assertEquals(12000.0, explicit.expectedAnnualMiles, 0.0)
    }

}
