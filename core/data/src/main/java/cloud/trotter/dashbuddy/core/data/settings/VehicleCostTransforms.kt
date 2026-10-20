package cloud.trotter.dashbuddy.core.data.settings

import cloud.trotter.dashbuddy.domain.model.vehicle.*

/** Shared field-only transforms, applied to the profile held under the garage write lock. */
object VehicleCostTransforms {
    fun tireCost(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(tireSetCost = value))

    fun tireLifetime(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(tireLifetimeMi = value))

    fun oilCost(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(oilCost = value))

    fun oilInterval(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(oilIntervalMi = value))

    fun brakesCost(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(brakesCost = value))

    fun brakesInterval(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(brakesIntervalMi = value))

    fun fluidsCost(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(fluidsCost = value))

    fun fluidsInterval(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(fluidsIntervalMi = value))

    fun miscYearly(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(miscYearly = value))

    fun miscMiles(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(miscYearlyMi = value))

    fun chainCost(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(chainCost = value))

    fun chainLifetime(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(chainLifeMi = value))

    fun batteryCost(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(batteryCost = value))

    fun batteryLifetime(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(batteryLifeMi = value))

    fun beltCost(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(beltCost = value))

    fun beltLifetime(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(beltLifeMi = value))

    fun depreciationInclude(profile: VehicleProfile, value: Boolean?): VehicleProfile =
        profile.withCosts(fixed = profile.fixed.copy(includeDepreciation = value))

    fun purchasePrice(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(fixed = profile.fixed.copy(purchasePrice = value))

    fun lifetimeMiles(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(fixed = profile.fixed.copy(totalLifetimeMi = value))

    fun insurance(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(fixed = profile.fixed.copy(insuranceDeltaPerMonth = value))

    fun registration(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(fixed = profile.fixed.copy(registrationDeltaPerYear = value))

    fun annualMiles(profile: VehicleProfile, value: Double?): VehicleProfile =
        profile.withCosts(fixed = profile.fixed.copy(expectedAnnualMiles = value))

    fun mpg(profile: VehicleProfile, mpg: Double?): VehicleProfile =
        profile.withGas(mpg = mpg)

    fun fuel(profile: VehicleProfile, type: FuelType): VehicleProfile =
        profile.withGas(fuelType = type)

    fun consumption(profile: VehicleProfile, value: Double?): VehicleProfile = when (profile) {
        is ElectricCar -> profile.copy(kwhPer100Mi = value)
        is ScooterMoped -> if (profile.powertrain == Powertrain.ELECTRIC) profile.copy(kwhPer100Mi = value) else profile
        is EBike -> profile.copy(whPerMile = value)
        else -> profile
    }

    private fun VehicleProfile.consumptionOverride(): Double? = when (this) {
        is ElectricCar -> kwhPer100Mi
        is ScooterMoped -> kwhPer100Mi
        is EBike -> whPerMile
        else -> null
    }

    // Null overrides encode following the estimate. Null results mean the CAS did not match.
    fun restoreTireCost(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.tireSetCost) == expected) tireCost(profile, previous) else null
    fun restoreTireLifetime(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.tireLifetimeMi) == expected) tireLifetime(profile, previous) else null
    fun restoreOilCost(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.oilCost) == expected) oilCost(profile, previous) else null
    fun restoreOilInterval(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.oilIntervalMi) == expected) oilInterval(profile, previous) else null
    fun restoreBrakesCost(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.brakesCost) == expected) brakesCost(profile, previous) else null
    fun restoreBrakesInterval(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.brakesIntervalMi) == expected) brakesInterval(profile, previous) else null
    fun restoreFluidsCost(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.fluidsCost) == expected) fluidsCost(profile, previous) else null
    fun restoreFluidsInterval(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.fluidsIntervalMi) == expected) fluidsInterval(profile, previous) else null
    fun restoreMiscYearly(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.miscYearly) == expected) miscYearly(profile, previous) else null
    fun restoreMiscMiles(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.miscYearlyMi) == expected) miscMiles(profile, previous) else null
    fun restoreChainCost(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.chainCost) == expected) chainCost(profile, previous) else null
    fun restoreChainLifetime(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.chainLifeMi) == expected) chainLifetime(profile, previous) else null
    fun restoreBatteryCost(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.batteryCost) == expected) batteryCost(profile, previous) else null
    fun restoreBatteryLifetime(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.batteryLifeMi) == expected) batteryLifetime(profile, previous) else null
    fun restoreBeltCost(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.beltCost) == expected) beltCost(profile, previous) else null
    fun restoreBeltLifetime(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.wear.beltLifeMi) == expected) beltLifetime(profile, previous) else null
    fun restoreDepreciationInclude(profile: VehicleProfile, previous: Boolean?, expected: Boolean?): VehicleProfile? =
        if ((profile.fixed.includeDepreciation) == expected) depreciationInclude(profile, previous) else null
    fun restorePurchasePrice(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.fixed.purchasePrice) == expected) purchasePrice(profile, previous) else null
    fun restoreLifetimeMiles(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.fixed.totalLifetimeMi) == expected) lifetimeMiles(profile, previous) else null
    fun restoreInsurance(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.fixed.insuranceDeltaPerMonth) == expected) insurance(profile, previous) else null
    fun restoreRegistration(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.fixed.registrationDeltaPerYear) == expected) registration(profile, previous) else null
    fun restoreAnnualMiles(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.fixed.expectedAnnualMiles) == expected) annualMiles(profile, previous) else null
    fun restoreMpg(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.gasMpg) == expected) mpg(profile, previous) else null
    fun restoreConsumption(profile: VehicleProfile, previous: Double?, expected: Double?): VehicleProfile? =
        if ((profile.consumptionOverride()) == expected) consumption(profile, previous) else null
    fun restoreFuel(profile: VehicleProfile, previous: FuelType, expected: FuelType): VehicleProfile? =
        if ((profile.gasFuel ?: FuelType.REGULAR) == expected) fuel(profile, previous) else null
}
