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

    // A null prior override restores following the type estimate.
    fun restoreTireCost(profile: VehicleProfile, previous: Double?): VehicleProfile = tireCost(profile, previous)
    fun restoreTireLifetime(profile: VehicleProfile, previous: Double?): VehicleProfile = tireLifetime(profile, previous)
    fun restoreOilCost(profile: VehicleProfile, previous: Double?): VehicleProfile = oilCost(profile, previous)
    fun restoreOilInterval(profile: VehicleProfile, previous: Double?): VehicleProfile = oilInterval(profile, previous)
    fun restoreBrakesCost(profile: VehicleProfile, previous: Double?): VehicleProfile = brakesCost(profile, previous)
    fun restoreBrakesInterval(profile: VehicleProfile, previous: Double?): VehicleProfile = brakesInterval(profile, previous)
    fun restoreFluidsCost(profile: VehicleProfile, previous: Double?): VehicleProfile = fluidsCost(profile, previous)
    fun restoreFluidsInterval(profile: VehicleProfile, previous: Double?): VehicleProfile = fluidsInterval(profile, previous)
    fun restoreMiscYearly(profile: VehicleProfile, previous: Double?): VehicleProfile = miscYearly(profile, previous)
    fun restoreMiscMiles(profile: VehicleProfile, previous: Double?): VehicleProfile = miscMiles(profile, previous)
    fun restoreChainCost(profile: VehicleProfile, previous: Double?): VehicleProfile = chainCost(profile, previous)
    fun restoreChainLifetime(profile: VehicleProfile, previous: Double?): VehicleProfile = chainLifetime(profile, previous)
    fun restoreBatteryCost(profile: VehicleProfile, previous: Double?): VehicleProfile = batteryCost(profile, previous)
    fun restoreBatteryLifetime(profile: VehicleProfile, previous: Double?): VehicleProfile = batteryLifetime(profile, previous)
    fun restoreBeltCost(profile: VehicleProfile, previous: Double?): VehicleProfile = beltCost(profile, previous)
    fun restoreBeltLifetime(profile: VehicleProfile, previous: Double?): VehicleProfile = beltLifetime(profile, previous)
    fun restoreDepreciationInclude(profile: VehicleProfile, previous: Boolean?): VehicleProfile = depreciationInclude(profile, previous)
    fun restorePurchasePrice(profile: VehicleProfile, previous: Double?): VehicleProfile = purchasePrice(profile, previous)
    fun restoreLifetimeMiles(profile: VehicleProfile, previous: Double?): VehicleProfile = lifetimeMiles(profile, previous)
    fun restoreInsurance(profile: VehicleProfile, previous: Double?): VehicleProfile = insurance(profile, previous)
    fun restoreRegistration(profile: VehicleProfile, previous: Double?): VehicleProfile = registration(profile, previous)
    fun restoreAnnualMiles(profile: VehicleProfile, previous: Double?): VehicleProfile = annualMiles(profile, previous)
    fun restoreMpg(profile: VehicleProfile, previous: Double?): VehicleProfile = mpg(profile, previous)
    fun restoreConsumption(profile: VehicleProfile, previous: Double?): VehicleProfile = consumption(profile, previous)
    fun restoreFuel(profile: VehicleProfile, previous: FuelType): VehicleProfile = fuel(profile, previous)
}
