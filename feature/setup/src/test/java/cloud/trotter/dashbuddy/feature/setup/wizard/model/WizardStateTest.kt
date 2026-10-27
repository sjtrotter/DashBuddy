package cloud.trotter.dashbuddy.feature.setup.wizard.model

import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.model.vehicle.EBike
import cloud.trotter.dashbuddy.domain.model.vehicle.ElectricCar
import cloud.trotter.dashbuddy.domain.model.vehicle.FixedCosts
import cloud.trotter.dashbuddy.domain.model.vehicle.GasCar
import cloud.trotter.dashbuddy.domain.model.vehicle.ScooterMoped
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.model.vehicle.WearProfile
import cloud.trotter.dashbuddy.feature.setup.wizard.vehicle.VehicleDraftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardStateTest {
    @Test
    fun `vehicle projection retains EV consumption and applies the host tire override`() {
        val fixed = FixedCosts(insuranceDeltaPerMonth = 25.0)
        val vehicle = VehicleDraftState(
            profile = ElectricCar(kwhPer100Mi = 40.0,
                wear = WearProfile(tireSetCost = 800.0, tireLifetimeMi = 45_000.0), fixed = fixed),
            vehicleClass = VehicleClass.EV, kwhPer100Mi = 27.0,
            userSetFields = setOf(EconomyField.KWH_PER_100_MI),
        )
        val host = WizardState(wear = WearProfile(tireSetCost = 900.0), userSetEconomyFields = setOf(EconomyField.TIRE_COST))

        // The complete result of the previous combined vehicle-and-host projection.
        assertEquals(ElectricCar(kwhPer100Mi = 27.0,
            wear = WearProfile(tireSetCost = 900.0, tireLifetimeMi = 45_000.0), fixed = fixed),
            host.toVehicleProfile(vehicle))

        // The legacy mirror writes the profile's stored overrides with their stored values, not the host's defaults.
        val mirrored = host.toEconomyDraft(vehicle)
        assertEquals(900.0, mirrored.wear.tireSetCost)
        assertEquals(45_000.0, mirrored.wear.tireLifetimeMi)
        assertEquals(25.0, mirrored.fixed.insuranceDeltaPerMonth)
        assertEquals(27.0, mirrored.kwhPer100Mi, 0.0)
        assertTrue(mirrored.userSetFields.containsAll(setOf(EconomyField.TIRE_COST, EconomyField.TIRE_LIFETIME,
            EconomyField.INSURANCE_DELTA, EconomyField.KWH_PER_100_MI)))
    }

    @Test
    fun `the mirror draft marks only costs that apply to the saved vehicle`() {
        val host = WizardState(
            wear = WearProfile(oilCost = 75.0, oilIntervalMi = 6_000.0, chainCost = 70.0),
            userSetEconomyFields = setOf(EconomyField.OIL_COST, EconomyField.OIL_INTERVAL,
                EconomyField.CHAIN_COST, EconomyField.PHONE_PLAN_TOTAL),
        )

        val bike = host.toEconomyDraft(VehicleDraftState(profile = EBike(), vehicleClass = VehicleClass.E_BIKE))
        assertEquals(setOf(EconomyField.CHAIN_COST, EconomyField.PHONE_PLAN_TOTAL), bike.userSetFields)

        val car = host.toEconomyDraft(VehicleDraftState(profile = GasCar(), vehicleClass = VehicleClass.SEDAN))
        assertEquals(setOf(EconomyField.OIL_COST, EconomyField.OIL_INTERVAL, EconomyField.PHONE_PLAN_TOTAL),
            car.userSetFields)
    }

    @Test
    fun `all cost edits project applicable values and type defaults retain fixed allocations`() {
        val vehicle = VehicleDraftState(profile = GasCar(), vehicleClass = VehicleClass.SEDAN)
        val host = WizardState(
            wear = WearProfile(
                tireSetCost = 900.0, tireLifetimeMi = 45_000.0,
                oilCost = 75.0, oilIntervalMi = 6_000.0,
                brakesCost = 450.0, brakesIntervalMi = 50_000.0,
                fluidsCost = 180.0, fluidsIntervalMi = 35_000.0,
                miscYearly = 600.0, miscYearlyMi = 15_000.0,
                chainCost = 70.0, chainLifeMi = 4_000.0,
                batteryCost = 700.0, batteryLifeMi = 25_000.0,
                beltCost = 80.0, beltLifeMi = 12_000.0,
            ),
            fixed = FixedCosts(
                includeDepreciation = false, purchasePrice = 32_000.0, totalLifetimeMi = 240_000.0,
                insuranceDeltaPerMonth = 35.0, registrationDeltaPerYear = 120.0, expectedAnnualMiles = 18_000.0,
            ),
            avgMinutesPerMile = 4.0, basePickupMinutes = 9.0,
            userSetEconomyFields = setOf(
                EconomyField.TIRE_COST, EconomyField.TIRE_LIFETIME,
                EconomyField.OIL_COST, EconomyField.OIL_INTERVAL,
                EconomyField.BRAKES_COST, EconomyField.BRAKES_INTERVAL,
                EconomyField.FLUIDS_COST, EconomyField.FLUIDS_INTERVAL,
                EconomyField.MISC_YEARLY, EconomyField.MISC_YEARLY_MI,
                EconomyField.CHAIN_COST, EconomyField.CHAIN_LIFETIME,
                EconomyField.BATTERY_COST, EconomyField.BATTERY_LIFETIME,
                EconomyField.BELT_COST, EconomyField.BELT_LIFETIME,
                EconomyField.INCLUDE_DEPRECIATION, EconomyField.PURCHASE_PRICE, EconomyField.TOTAL_LIFETIME_MI,
                EconomyField.INSURANCE_DELTA, EconomyField.REGISTRATION_DELTA, EconomyField.EXPECTED_ANNUAL_MI,
            ),
        )

        val profile = host.toVehicleProfile(vehicle)
        assertEquals(900.0, profile.wear.tireSetCost)
        assertEquals(45_000.0, profile.wear.tireLifetimeMi)
        assertEquals(75.0, profile.wear.oilCost)
        assertEquals(6_000.0, profile.wear.oilIntervalMi)
        assertEquals(450.0, profile.wear.brakesCost)
        assertEquals(50_000.0, profile.wear.brakesIntervalMi)
        assertEquals(180.0, profile.wear.fluidsCost)
        assertEquals(35_000.0, profile.wear.fluidsIntervalMi)
        assertEquals(600.0, profile.wear.miscYearly)
        assertEquals(15_000.0, profile.wear.miscYearlyMi)
        assertNull(profile.wear.chainCost)
        assertNull(profile.wear.chainLifeMi)
        assertNull(profile.wear.batteryCost)
        assertNull(profile.wear.batteryLifeMi)
        assertNull(profile.wear.beltCost)
        assertNull(profile.wear.beltLifeMi)
        assertEquals(false, profile.fixed.includeDepreciation)
        assertEquals(32_000.0, profile.fixed.purchasePrice)
        assertEquals(240_000.0, profile.fixed.totalLifetimeMi)
        assertEquals(35.0, profile.fixed.insuranceDeltaPerMonth)
        assertEquals(120.0, profile.fixed.registrationDeltaPerYear)
        assertEquals(18_000.0, profile.fixed.expectedAnnualMiles)

        val bike = host.toVehicleProfile(VehicleDraftState(profile = EBike(), vehicleClass = VehicleClass.E_BIKE))
        assertEquals(70.0, bike.wear.chainCost)
        assertEquals(4_000.0, bike.wear.chainLifeMi)
        assertEquals(700.0, bike.wear.batteryCost)
        assertEquals(25_000.0, bike.wear.batteryLifeMi)
        val scooter = host.toVehicleProfile(VehicleDraftState(profile = ScooterMoped()))
        assertEquals(80.0, scooter.wear.beltCost)
        assertEquals(12_000.0, scooter.wear.beltLifeMi)

        val retained = host.withTypeDefaults(vehicle)
        assertEquals(host.wear.copy(chainCost = 0.0, chainLifeMi = 1.0,
            batteryCost = 0.0, batteryLifeMi = 1.0, beltCost = 0.0, beltLifeMi = 1.0), retained.wear)
        assertEquals(host.fixed, retained.fixed)

        // A type change clears cost marks before refreshing the resolved defaults.
        val refreshed = host.copy(userSetEconomyFields = emptySet()).withTypeDefaults(vehicle)
        val defaults = WizardState()
        assertEquals(defaults.wear, refreshed.wear)
        assertEquals(defaults.fixed.purchasePrice, refreshed.fixed.purchasePrice)
        assertEquals(defaults.fixed.totalLifetimeMi, refreshed.fixed.totalLifetimeMi)
        assertEquals(false, refreshed.fixed.includeDepreciation)
        assertEquals(35.0, refreshed.fixed.insuranceDeltaPerMonth)
        assertEquals(120.0, refreshed.fixed.registrationDeltaPerYear)
        assertEquals(18_000.0, refreshed.fixed.expectedAnnualMiles)
        assertEquals(defaults.avgMinutesPerMile, refreshed.avgMinutesPerMile, 0.0)
        assertEquals(defaults.basePickupMinutes, refreshed.basePickupMinutes, 0.0)
    }
}
