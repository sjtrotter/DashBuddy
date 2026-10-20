package cloud.trotter.dashbuddy.core.data.settings

import cloud.trotter.dashbuddy.domain.model.vehicle.*
import org.junit.Assert.*
import org.junit.Test

class VehicleCostTransformsTest {
    private data class PairCase(
        val transform: (VehicleProfile, Double?, Double?) -> VehicleProfile,
        val wear: (Double?, Double?) -> WearProfile,
    )

    private val pairs = listOf(
        PairCase(VehicleCostTransforms::tires) { cost, miles -> WearProfile(tireSetCost = cost, tireLifetimeMi = miles) },
        PairCase(VehicleCostTransforms::oil) { cost, miles -> WearProfile(oilCost = cost, oilIntervalMi = miles) },
        PairCase(VehicleCostTransforms::brakes) { cost, miles -> WearProfile(brakesCost = cost, brakesIntervalMi = miles) },
        PairCase(VehicleCostTransforms::fluids) { cost, miles -> WearProfile(fluidsCost = cost, fluidsIntervalMi = miles) },
        PairCase(VehicleCostTransforms::misc) { cost, miles -> WearProfile(miscYearly = cost, miscYearlyMi = miles) },
        PairCase(VehicleCostTransforms::chain) { cost, miles -> WearProfile(chainCost = cost, chainLifeMi = miles) },
        PairCase(VehicleCostTransforms::battery) { cost, miles -> WearProfile(batteryCost = cost, batteryLifeMi = miles) },
        PairCase(VehicleCostTransforms::belt) { cost, miles -> WearProfile(beltCost = cost, beltLifeMi = miles) },
    )

    @Test fun `each pair preserves the untouched half including fractional intervals and presets`() {
        for (pair in pairs) {
            val original = GasCar(year = "2020", make = "Honda", model = "Civic",
                wear = pair.wear(60.0, 5000.25), fixed = FixedCosts(purchasePrice = 12345.67))
            assertEquals(original.copy(wear = pair.wear(65.0, 5000.25)), pair.transform(original, 65.0, null))
            assertEquals(original.copy(wear = pair.wear(60.0, 6000.0)), pair.transform(original, null, 6000.0))
            assertEquals(original, pair.transform(original, null, null))
            val preset = original.copy(wear = pair.wear(null, null))
            assertEquals(preset.copy(wear = pair.wear(65.0, null)), pair.transform(preset, 65.0, null))
            assertEquals(preset.copy(wear = pair.wear(null, 6000.0)), pair.transform(preset, null, 6000.0))
        }
    }

    @Test fun `depreciation switch preserves queued price edits and values preserve the switch`() {
        val original = GasCar(fixed = FixedCosts(includeDepreciation = true,
            purchasePrice = 10000.25, totalLifetimeMi = 200000.25))
        val priced = VehicleCostTransforms.depreciationValues(original, 12000.0, null)
        val toggled = VehicleCostTransforms.depreciationInclude(priced, false)
        assertEquals(original.copy(fixed = original.fixed.copy(
            includeDepreciation = false, purchasePrice = 12000.0)), toggled)
        assertEquals(toggled.withCosts(fixed = toggled.fixed.copy(totalLifetimeMi = 180000.0)),
            VehicleCostTransforms.depreciationValues(toggled, null, 180000.0))
        assertEquals(original, VehicleCostTransforms.depreciationValues(original, null, null))
    }
}
