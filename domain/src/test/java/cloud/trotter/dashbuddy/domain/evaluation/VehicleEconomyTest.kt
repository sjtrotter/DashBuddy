package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleProfile
import org.junit.Assert.*
import org.junit.Test

class VehicleEconomyTest {
    @Test fun `class defaults and nullable overrides stay per car while shared inputs stay global`() {
        val base = UserEconomy(gasPricePerGallon = 4.25, avgMinutesPerMile = 3.0,
            phonePlanTotal = 120.0, phoneBusinessPercent = 40.0)
        val compact = VehicleEconomy.apply(base, VehicleProfile(vehicleClass = VehicleClass.COMPACT,
            mpg = 36.0, tireSetCost = 700.0, expectedAnnualMiles = 15_000.0))
        val truck = VehicleEconomy.apply(base, VehicleProfile(vehicleClass = VehicleClass.TRUCK))
        assertEquals(36.0, compact.vehicleMpg, 0.0)
        assertEquals(700.0, compact.tireSetCost, 0.0)
        assertEquals(15_000.0, compact.expectedAnnualMiles, 0.0)
        assertEquals(VehicleClass.TRUCK.defaultMpg, truck.vehicleMpg, 0.0)
        assertEquals(VehicleClass.TRUCK.tireSetCost, truck.tireSetCost, 0.0)
        assertEquals(4.25, truck.gasPricePerGallon, 0.0)
        assertEquals(3.0, truck.avgMinutesPerMile, 0.0)
        assertEquals(120.0, truck.phonePlanTotal, 0.0)
        assertEquals(40.0, truck.phoneBusinessPercent, 0.0)
    }
    @Test fun `draft overrides preserve untouched nulls and commit explicit cost groups`() {
        val original = VehicleProfile(make = "Honda", mpg = 31.0, oilCost = 90.0)
        val draft = UserEconomy(tireSetCost = 720.0, tireLifetimeMi = 48_000.0,
            includeDepreciation = false, purchasePrice = 25_000.0, totalLifetimeMi = 180_000.0,
            insuranceDeltaPerMonth = 0.0,
            userSetFields = setOf(EconomyField.TIRE_COST, EconomyField.PURCHASE_PRICE, EconomyField.INSURANCE_DELTA))
        assertTrue(VehicleEconomy.hasCostOverrides(draft))
        assertEquals(original.copy(tireSetCost = 720.0, tireLifetimeMi = 48_000.0,
            includeDepreciation = false, purchasePrice = 25_000.0, totalLifetimeMi = 180_000.0,
            insuranceDeltaPerMonth = 0.0), VehicleEconomy.updateProfile(original, draft))
        val sharedOnly = UserEconomy(userSetFields = setOf(EconomyField.PHONE_PLAN_TOTAL, EconomyField.AVG_MIN_PER_MILE))
        assertFalse(VehicleEconomy.hasCostOverrides(sharedOnly))
        assertEquals(original, VehicleEconomy.updateProfile(original, sharedOnly))
    }
    @Test fun `a complete economy draft includes every vehicle cost override`() {
        val draft = UserEconomy(tireSetCost = 720.0, tireLifetimeMi = 48_000.0,
            oilCost = 85.0, oilIntervalMi = 7_500.0, brakesCost = 600.0, brakesIntervalMi = 50_000.0,
            fluidsCost = 125.0, fluidsIntervalMi = 30_000.0, miscYearly = 800.0, miscYearlyMi = 12_000.0,
            includeDepreciation = false, purchasePrice = 28_000.0, totalLifetimeMi = 180_000.0,
            insuranceDeltaPerMonth = 75.0, registrationDeltaPerYear = 120.0, expectedAnnualMiles = 15_000.0,
            userSetFields = EconomyField.entries.toSet())
        assertEquals(VehicleProfile(tireSetCost = 720.0, tireLifetimeMi = 48_000.0,
            oilCost = 85.0, oilIntervalMi = 7_500.0, brakesCost = 600.0, brakesIntervalMi = 50_000.0,
            fluidsCost = 125.0, fluidsIntervalMi = 30_000.0, miscYearly = 800.0, miscYearlyMi = 12_000.0,
            includeDepreciation = false, purchasePrice = 28_000.0, totalLifetimeMi = 180_000.0,
            insuranceDeltaPerMonth = 75.0, registrationDeltaPerYear = 120.0, expectedAnnualMiles = 15_000.0),
            VehicleEconomy.updateProfile(VehicleProfile(), draft))
    }

    @Test fun `a zero mpg falls back to the class default`() {
        for (mpg in listOf(0.0, -1.0)) {
            val result = VehicleEconomy.apply(UserEconomy(gasPricePerGallon = 4.0),
                VehicleProfile(vehicleClass = VehicleClass.COMPACT, mpg = mpg))
            assertEquals(VehicleClass.COMPACT.defaultMpg, result.vehicleMpg, 0.0)
            assertFalse(EconomyField.VEHICLE_MPG in result.userSetFields)
        }
    }

    @Test fun `every EconomyField belongs to exactly one group`() {
        val fields = EconomyFieldGroups.entries.flatMap { it.fields }
        assertEquals(EconomyField.entries.toSet(), fields.toSet())
        assertEquals(EconomyField.entries.size, fields.size)
    }
}
