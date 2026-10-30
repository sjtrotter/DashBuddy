package cloud.trotter.log.gig.ui.main.settings.garage

import cloud.trotter.log.gig.domain.evaluation.EconomyField
import cloud.trotter.log.gig.domain.evaluation.SharedEconomy
import cloud.trotter.log.gig.domain.evaluation.VehicleEconomyInputs
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.model.vehicle.*
import org.junit.Assert.*
import org.junit.Test

class VehicleFactsMapperTest {
    private fun map(profile: VehicleProfile) = VehicleFactsMapper.map(
        Vehicle("car", null, 1, profile), SharedEconomy(), VehicleUsage(0, null), null)

    @Test fun `gas per mile parts match the evaluator inputs and sum to total`() {
        val profile = GasCar(mpg = 31.0, fuelType = FuelType.PREMIUM,
            wear = WearProfile(tireSetCost = 800.0, tireLifetimeMi = 40000.0),
            fixed = FixedCosts(insuranceDeltaPerMonth = 20.0))
        val inputs = VehicleEconomyInputs.from(profile, SharedEconomy())
        val facts = map(profile)
        assertEquals(inputs.energyPerMile, facts.energyPerMile, 0.0)
        assertEquals(inputs.wearPerMile, facts.wearPerMile, 0.0)
        assertEquals(inputs.depreciationPerMile, facts.depreciationPerMile, 0.0)
        assertEquals(inputs.fixedPerMile, facts.fixedPerMile, 0.0)
        assertEquals(facts.energyPerMile + facts.wearPerMile + facts.depreciationPerMile + facts.fixedPerMile,
            facts.costPerMile, 0.0)
        assertTrue(facts.costs.single { it.field == EconomyField.VEHICLE_MPG }.value.contains(FuelType.PREMIUM.displayName))
        val tires = facts.costs.single { it.field == EconomyField.TIRE_COST }
        assertFalse(tires.estimate)
        assertTrue(tires.value.contains(Formats.money(800.0)))
        assertTrue(tires.value.contains(Formats.commaInt(40000)))
    }

    @Test fun `null cost or interval is an estimate and explicit zero is not`() {
        val facts = map(GasCar(wear = WearProfile(tireSetCost = 0.0, tireLifetimeMi = 40000.0,
            oilCost = 50.0)))
        assertTrue(facts.estimated)
        assertFalse(facts.costs.single { it.field == EconomyField.TIRE_COST }.estimate)
        assertTrue(facts.costs.single { it.field == EconomyField.OIL_COST }.estimate)
        assertTrue(facts.costs.single { it.field == EconomyField.BRAKES_COST }.estimate)
        assertTrue(facts.costs.single { it.field == EconomyField.VEHICLE_MPG }.estimate)
    }

    /** #1383 review: a price the dasher never set is the built-in default, marked as an estimate. */
    @Test fun `fuel and electricity prices are estimates unless the dasher set them`() {
        val car = Vehicle("car", null, 1, GasCar())
        val unset = VehicleFactsMapper.map(car, SharedEconomy(), VehicleUsage(0, null), null)
        assertTrue(unset.costs.single { it.field == EconomyField.GAS_PRICE }.estimate)
        val set = VehicleFactsMapper.map(car, SharedEconomy(gasPricePerGallon = 3.1,
            userSetFields = setOf(EconomyField.GAS_PRICE)), VehicleUsage(0, null), null)
        assertFalse(set.costs.single { it.field == EconomyField.GAS_PRICE }.estimate)
    }

    @Test fun `EV lists kWh per 100 miles with no oil or gas`() {
        val facts = map(ElectricCar(kwhPer100Mi = 28.0))
        val consumption = facts.costs.single { it.field == EconomyField.KWH_PER_100_MI }
        assertEquals("${Formats.decimal(28.0)} kWh/100 mi", consumption.value)
        assertFalse(consumption.estimate)
        assertFalse(facts.costs.any { it.field in setOf(EconomyField.OIL_COST, EconomyField.VEHICLE_MPG, EconomyField.GAS_PRICE) })
    }

    @Test fun `bicycle lists no fuel or electricity lines`() {
        val facts = map(Bicycle())
        assertEquals(0.0, facts.energyPerMile, 0.0)
        assertFalse(facts.costs.any { it.field in setOf(EconomyField.VEHICLE_MPG, EconomyField.GAS_PRICE,
            EconomyField.KWH_PER_100_MI, EconomyField.WH_PER_MILE, EconomyField.ELECTRICITY_PRICE, EconomyField.OIL_COST) })
        assertTrue(facts.costs.any { it.field == EconomyField.CHAIN_COST })
    }

    @Test fun `depreciation and fixed deltas appear only when set`() {
        val fields = setOf(EconomyField.INCLUDE_DEPRECIATION, EconomyField.INSURANCE_DELTA, EconomyField.REGISTRATION_DELTA)
        assertFalse(map(GasCar()).costs.any { it.field in fields })
        val facts = map(GasCar(fixed = FixedCosts(includeDepreciation = false,
            insuranceDeltaPerMonth = 0.0, registrationDeltaPerYear = 50.0)))
        assertEquals(fields, facts.costs.filter { it.field in fields }.map { it.field }.toSet())
        assertEquals(Formats.money(0.0), facts.costs.single { it.field == EconomyField.INCLUDE_DEPRECIATION }.value)
    }

    @Test fun `usage last reading and retirement pass through`() {
        val usage = VehicleUsage(4, 120.5)
        val reading = LastOdometerReading(52388, 250.0, 10)
        val facts = VehicleFactsMapper.map(Vehicle("car", null, 1, retiredAt = 11), SharedEconomy(), usage, reading)
        assertEquals(usage, facts.usage)
        assertEquals(reading, facts.lastReading)
        assertEquals(11L, facts.retiredAt)
    }
}
