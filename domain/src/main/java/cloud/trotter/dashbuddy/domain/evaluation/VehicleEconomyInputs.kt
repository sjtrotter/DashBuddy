package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.vehicle.*

data class TravelProfile(val minutesPerMile: Double, val basePickupMinutes: Double)

/** The evaluator's common denominator, with optional immutable detail for the hub/editor. */
data class VehicleEconomyInputs(
    val energyPerMile: Double,
    val wearPerMile: Double,
    val depreciationPerMile: Double,
    val fixedPerMile: Double,
    val travel: TravelProfile,
    val disclosure: VehicleEconomyDisclosure? = null,
) {
    companion object {
        fun from(profile: VehicleProfile, shared: SharedEconomy): VehicleEconomyInputs {
            val preset = VehiclePresets.forProfile(profile)
            val active = EconomyFieldGroups.fieldsFor(profile)
            val w = profile.wear
            val d = preset.wear
            val wear = WearProfile(
                tireSetCost = if (EconomyField.TIRE_COST in active) w.tireSetCost ?: d.tireSetCost ?: 0.0 else 0.0,
                tireLifetimeMi = if (EconomyField.TIRE_LIFETIME in active) w.tireLifetimeMi ?: d.tireLifetimeMi ?: 1.0 else 1.0,
                oilCost = if (EconomyField.OIL_COST in active) w.oilCost ?: d.oilCost ?: 0.0 else 0.0,
                oilIntervalMi = if (EconomyField.OIL_INTERVAL in active) w.oilIntervalMi ?: d.oilIntervalMi ?: 1.0 else 1.0,
                brakesCost = if (EconomyField.BRAKES_COST in active) w.brakesCost ?: d.brakesCost ?: 0.0 else 0.0,
                brakesIntervalMi = if (EconomyField.BRAKES_INTERVAL in active) w.brakesIntervalMi ?: d.brakesIntervalMi ?: 1.0 else 1.0,
                fluidsCost = if (EconomyField.FLUIDS_COST in active) w.fluidsCost ?: d.fluidsCost ?: 0.0 else 0.0,
                fluidsIntervalMi = if (EconomyField.FLUIDS_INTERVAL in active) w.fluidsIntervalMi ?: d.fluidsIntervalMi ?: 1.0 else 1.0,
                chainCost = if (EconomyField.CHAIN_COST in active) w.chainCost ?: d.chainCost ?: 0.0 else 0.0,
                chainLifeMi = if (EconomyField.CHAIN_LIFETIME in active) w.chainLifeMi ?: d.chainLifeMi ?: 1.0 else 1.0,
                batteryCost = if (EconomyField.BATTERY_COST in active) w.batteryCost ?: d.batteryCost ?: 0.0 else 0.0,
                batteryLifeMi = if (EconomyField.BATTERY_LIFETIME in active) w.batteryLifeMi ?: d.batteryLifeMi ?: 1.0 else 1.0,
                beltCost = if (EconomyField.BELT_COST in active) w.beltCost ?: d.beltCost ?: 0.0 else 0.0,
                beltLifeMi = if (EconomyField.BELT_LIFETIME in active) w.beltLifeMi ?: d.beltLifeMi ?: 1.0 else 1.0,
                miscYearly = if (EconomyField.MISC_YEARLY in active) w.miscYearly ?: d.miscYearly ?: 0.0 else 0.0,
                miscYearlyMi = if (EconomyField.MISC_YEARLY_MI in active) w.miscYearlyMi ?: d.miscYearlyMi ?: 1.0 else 1.0,
            )
            val f = profile.fixed
            val fixed = FixedCosts(
                f.includeDepreciation ?: preset.fixed.includeDepreciation ?: true,
                f.purchasePrice ?: preset.fixed.purchasePrice ?: 0.0,
                f.totalLifetimeMi ?: preset.fixed.totalLifetimeMi ?: 1.0,
                f.insuranceDeltaPerMonth ?: 0.0, f.registrationDeltaPerYear ?: 0.0,
                f.expectedAnnualMiles ?: preset.fixed.expectedAnnualMiles ?: UserEconomy.DEFAULT_ANNUAL_MI,
            )
            fun rate(cost: Double?, life: Double?) = (cost ?: 0.0) / (life ?: 1.0).coerceAtLeast(1.0)
            val energy = when (profile) {
                is GasCar, is HybridCar, is Motorcycle -> shared.gasPricePerGallon /
                    (profile.gasMpg?.takeIf { it > 0 } ?: preset.mpg).coerceAtLeast(1.0)
                is ElectricCar -> (profile.kwhPer100Mi ?: VehiclePresets.EV_KWH_PER_100_MI) / 100.0 * shared.electricityPricePerKwh
                is ScooterMoped -> if (profile.powertrain == Powertrain.GAS) shared.gasPricePerGallon /
                    (profile.mpg?.takeIf { it > 0 } ?: preset.mpg).coerceAtLeast(1.0)
                    else (profile.kwhPer100Mi ?: VehiclePresets.SCOOTER_KWH_PER_100_MI) / 100.0 * shared.electricityPricePerKwh
                is EBike -> (profile.whPerMile ?: VehiclePresets.EBIKE_WH_PER_MILE) / 1000.0 * shared.electricityPricePerKwh
                is Bicycle -> 0.0
            }
            val maintenance = rate(wear.tireSetCost, wear.tireLifetimeMi) + rate(wear.oilCost, wear.oilIntervalMi) +
                rate(wear.brakesCost, wear.brakesIntervalMi) + rate(wear.fluidsCost, wear.fluidsIntervalMi) +
                rate(wear.chainCost, wear.chainLifeMi) + rate(wear.batteryCost, wear.batteryLifeMi) +
                rate(wear.beltCost, wear.beltLifeMi) + rate(wear.miscYearly, wear.miscYearlyMi)
            val depreciation = if (fixed.includeDepreciation == true) rate(fixed.purchasePrice, fixed.totalLifetimeMi) else 0.0
            val allocated = ((fixed.insuranceDeltaPerMonth ?: 0.0) * 12.0 + (fixed.registrationDeltaPerYear ?: 0.0)) /
                fixed.expectedAnnualMiles!!.coerceAtLeast(1.0)
            return VehicleEconomyInputs(energy, maintenance, depreciation, allocated, preset.travel,
                VehicleEconomyDisclosure(profile, wear, fixed))
        }
    }
}

/** Retains resolved human units so disclosure rows show the same inputs as the aggregate rates. */
data class VehicleEconomyDisclosure(val profile: VehicleProfile, val wear: WearProfile, val fixed: FixedCosts)
