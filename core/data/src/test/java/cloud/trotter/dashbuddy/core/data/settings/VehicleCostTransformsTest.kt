package cloud.trotter.dashbuddy.core.data.settings

import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.EconomyFieldGroups
import org.junit.Assert.*
import org.junit.Test

class VehicleCostTransformsTest {
    @Test fun `oil cost changes only its field and marker including a fractional sibling`() {
        val original = GasCar(year = "2020", make = "Honda", model = "Civic", mpg = 30.0,
            wear = WearProfile(oilIntervalMi = 5000.25), fixed = FixedCosts(purchasePrice = 12345.67))
        val changed = VehicleCostTransforms.oilCost(original, 65.0)
        assertEquals(original.copy(wear = original.wear.copy(oilCost = 65.0)), changed)
        assertEquals(EconomyFieldGroups.userSetFields(original) + EconomyField.OIL_COST,
            EconomyFieldGroups.userSetFields(changed))
    }
    @Test fun `price then include preserves the queued price and fractional lifetime`() {
        val original = GasCar(fixed = FixedCosts(includeDepreciation = true,
            purchasePrice = 10000.25, totalLifetimeMi = 200000.25))
        val priced = VehicleCostTransforms.purchasePrice(original, 12000.0)
        val toggled = VehicleCostTransforms.depreciationInclude(priced, false)
        assertEquals(original.copy(fixed = original.fixed.copy(
            includeDepreciation = false, purchasePrice = 12000.0)), toggled)
    }
    @Test fun `wear transforms retain identity consumption fixed costs and all siblings for every type`() {
        val transforms = listOf<Pair<(VehicleProfile, Double) -> VehicleProfile, EconomyField>>(
            VehicleCostTransforms::tireCost to EconomyField.TIRE_COST,
            VehicleCostTransforms::tireLifetime to EconomyField.TIRE_LIFETIME,
            VehicleCostTransforms::oilCost to EconomyField.OIL_COST,
            VehicleCostTransforms::oilInterval to EconomyField.OIL_INTERVAL,
            VehicleCostTransforms::brakesCost to EconomyField.BRAKES_COST,
            VehicleCostTransforms::brakesInterval to EconomyField.BRAKES_INTERVAL,
            VehicleCostTransforms::fluidsCost to EconomyField.FLUIDS_COST,
            VehicleCostTransforms::fluidsInterval to EconomyField.FLUIDS_INTERVAL,
            VehicleCostTransforms::miscYearly to EconomyField.MISC_YEARLY,
            VehicleCostTransforms::miscMiles to EconomyField.MISC_YEARLY_MI,
            VehicleCostTransforms::chainCost to EconomyField.CHAIN_COST,
            VehicleCostTransforms::chainLifetime to EconomyField.CHAIN_LIFETIME,
            VehicleCostTransforms::batteryCost to EconomyField.BATTERY_COST,
            VehicleCostTransforms::batteryLifetime to EconomyField.BATTERY_LIFETIME,
            VehicleCostTransforms::beltCost to EconomyField.BELT_COST,
            VehicleCostTransforms::beltLifetime to EconomyField.BELT_LIFETIME,
        )
        for (type in VehicleType.entries) for ((transform, field) in transforms) {
            val original = type.defaultProfile().withIdentity("2020", "A", "B")
                .withCosts(fixed = FixedCosts(purchasePrice = 12345.67))
            val changed = transform(original, 65.0)
            assertEquals(original.withCosts(wear = changed.wear), changed)
            // Fields inapplicable to a type never appear as applicable override markers.
            assertEquals(setOf(field).intersect(EconomyFieldGroups.fieldsFor(original)),
                EconomyFieldGroups.userSetFields(changed) - EconomyFieldGroups.userSetFields(original))
        }
    }

    @Test fun `oil undo restores estimate or explicit value while retaining a later interval`() {
        for (prior in listOf(null, 50.0)) {
            val original = GasCar(wear = WearProfile(oilCost = prior, oilIntervalMi = 5000.25))
            val saved = VehicleCostTransforms.oilCost(original, 65.0)
            val later = VehicleCostTransforms.oilInterval(saved, 7000.5)
            val undone = VehicleCostTransforms.restoreOilCost(later, prior)
            assertEquals(original.copy(wear = original.wear.copy(oilIntervalMi = 7000.5)), undone)
            assertEquals(prior != null, EconomyField.OIL_COST in EconomyFieldGroups.userSetFields(undone))
        }
    }

    @Test fun `propulsion and depreciation undo restore null overrides for all applicable types`() {
        for (original in listOf(GasCar(), HybridCar(), Motorcycle(), ScooterMoped())) {
            assertEquals(original, VehicleCostTransforms.restoreMpg(VehicleCostTransforms.mpg(original, 45.0), null))
            assertEquals(original, VehicleCostTransforms.restoreFuel(VehicleCostTransforms.fuel(original, FuelType.PREMIUM), FuelType.REGULAR))
        }
        for (original in listOf(ElectricCar(), ScooterMoped(powertrain = Powertrain.ELECTRIC), EBike())) {
            assertEquals(original, VehicleCostTransforms.restoreConsumption(VehicleCostTransforms.consumption(original, 40.0), null))
        }
        val original = GasCar(fixed = FixedCosts(purchasePrice = 12000.25))
        assertEquals(original, VehicleCostTransforms.restoreDepreciationInclude(
            VehicleCostTransforms.depreciationInclude(original, false), null))
    }

}
