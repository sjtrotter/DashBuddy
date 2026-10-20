package cloud.trotter.dashbuddy.core.data.settings

import cloud.trotter.dashbuddy.domain.model.vehicle.*

/** Shared field-only transforms, applied to the profile held under the garage write lock. */
object VehicleCostTransforms {
    fun tires(profile: VehicleProfile, setCost: Double?, lifetimeMi: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(tireSetCost = setCost ?: profile.wear.tireSetCost, tireLifetimeMi = lifetimeMi ?: profile.wear.tireLifetimeMi))

    fun oil(profile: VehicleProfile, cost: Double?, intervalMi: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(oilCost = cost ?: profile.wear.oilCost, oilIntervalMi = intervalMi ?: profile.wear.oilIntervalMi))

    fun brakes(profile: VehicleProfile, cost: Double?, intervalMi: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(brakesCost = cost ?: profile.wear.brakesCost, brakesIntervalMi = intervalMi ?: profile.wear.brakesIntervalMi))

    fun fluids(profile: VehicleProfile, cost: Double?, intervalMi: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(fluidsCost = cost ?: profile.wear.fluidsCost, fluidsIntervalMi = intervalMi ?: profile.wear.fluidsIntervalMi))

    fun misc(profile: VehicleProfile, yearly: Double?, yearlyMi: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(miscYearly = yearly ?: profile.wear.miscYearly, miscYearlyMi = yearlyMi ?: profile.wear.miscYearlyMi))

    fun depreciationInclude(profile: VehicleProfile, include: Boolean): VehicleProfile =
        profile.withCosts(fixed = profile.fixed.copy(includeDepreciation = include))

    fun depreciationValues(profile: VehicleProfile, purchasePrice: Double?, totalLifetimeMi: Double?): VehicleProfile =
        profile.withCosts(fixed = profile.fixed.copy(
            purchasePrice = purchasePrice ?: profile.fixed.purchasePrice,
            totalLifetimeMi = totalLifetimeMi ?: profile.fixed.totalLifetimeMi,
        ))

    fun insurance(profile: VehicleProfile, perMonth: Double): VehicleProfile =
        profile.withCosts(fixed = profile.fixed.copy(insuranceDeltaPerMonth = perMonth))

    fun registration(profile: VehicleProfile, perYear: Double): VehicleProfile =
        profile.withCosts(fixed = profile.fixed.copy(registrationDeltaPerYear = perYear))

    fun annualMiles(profile: VehicleProfile, miles: Double): VehicleProfile =
        profile.withCosts(fixed = profile.fixed.copy(expectedAnnualMiles = miles))

    fun chain(profile: VehicleProfile, cost: Double?, life: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(chainCost = cost ?: profile.wear.chainCost, chainLifeMi = life ?: profile.wear.chainLifeMi))

    fun battery(profile: VehicleProfile, cost: Double?, life: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(batteryCost = cost ?: profile.wear.batteryCost, batteryLifeMi = life ?: profile.wear.batteryLifeMi))

    fun belt(profile: VehicleProfile, cost: Double?, life: Double?): VehicleProfile =
        profile.withCosts(wear = profile.wear.copy(beltCost = cost ?: profile.wear.beltCost, beltLifeMi = life ?: profile.wear.beltLifeMi))

    fun mpg(profile: VehicleProfile, mpg: Double): VehicleProfile =
        profile.withGas(mpg = mpg)

    fun fuel(profile: VehicleProfile, type: FuelType): VehicleProfile =
        profile.withGas(fuelType = type)

    fun consumption(profile: VehicleProfile, value: Double): VehicleProfile = when (profile) {
        is ElectricCar -> profile.copy(kwhPer100Mi = value)
        is ScooterMoped -> if (profile.powertrain == Powertrain.ELECTRIC) profile.copy(kwhPer100Mi = value) else profile
        is EBike -> profile.copy(whPerMile = value)
        else -> profile
    }
}
