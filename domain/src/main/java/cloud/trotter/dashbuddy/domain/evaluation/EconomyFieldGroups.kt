package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleProfile

/** The atomic field groups shared by draft profile writes and legacy preference mirrors. */
object EconomyFieldGroups {
    class Group<T : Write>(
        val fields: Set<EconomyField>,
        val readFromEconomy: (UserEconomy) -> T,
        val applyToProfile: (VehicleProfile, T) -> VehicleProfile = { profile, _ -> profile },
    ) {
        fun updateProfile(profile: VehicleProfile, economy: UserEconomy): VehicleProfile =
            applyToProfile(profile, readFromEconomy(economy))
    }

    sealed interface Write
    data class VehicleClassValue(val value: VehicleClass) : Write
    data class Mpg(val value: Double) : Write
    data class GasPrice(val value: Double) : Write
    data class Tires(val cost: Double, val lifetime: Double) : Write
    data class Oil(val cost: Double, val interval: Double) : Write
    data class Brakes(val cost: Double, val interval: Double) : Write
    data class Fluids(val cost: Double, val interval: Double) : Write
    data class Misc(val yearly: Double, val miles: Double) : Write
    data class Depreciation(val include: Boolean, val price: Double, val lifetime: Double) : Write
    data class Insurance(val value: Double) : Write
    data class Registration(val value: Double) : Write
    data class AnnualMiles(val value: Double) : Write
    data class Phone(val total: Double, val lines: Int, val percent: Double) : Write
    data class Pace(val value: Double) : Write
    data class Pickup(val value: Double) : Write

    // Identity, MPG and pump price have dedicated save paths. Shared inputs leave
    // profiles unchanged; only the vehicle cost groups apply draft overrides here.
    val entries: List<Group<out Write>> = listOf(
        Group(
            fields = setOf(EconomyField.VEHICLE_CLASS),
            readFromEconomy = { VehicleClassValue(it.vehicleClass) },
        ),
        Group(
            fields = setOf(EconomyField.VEHICLE_MPG),
            readFromEconomy = { Mpg(it.vehicleMpg) },
        ),
        Group(
            fields = setOf(EconomyField.GAS_PRICE),
            readFromEconomy = { GasPrice(it.gasPricePerGallon) },
        ),
        Group(
            fields = setOf(EconomyField.TIRE_COST, EconomyField.TIRE_LIFETIME),
            readFromEconomy = { Tires(it.tireSetCost, it.tireLifetimeMi) },
            applyToProfile = { profile, value -> profile.copy(tireSetCost = value.cost, tireLifetimeMi = value.lifetime) },
        ),
        Group(
            fields = setOf(EconomyField.OIL_COST, EconomyField.OIL_INTERVAL),
            readFromEconomy = { Oil(it.oilCost, it.oilIntervalMi) },
            applyToProfile = { profile, value -> profile.copy(oilCost = value.cost, oilIntervalMi = value.interval) },
        ),
        Group(
            fields = setOf(EconomyField.BRAKES_COST, EconomyField.BRAKES_INTERVAL),
            readFromEconomy = { Brakes(it.brakesCost, it.brakesIntervalMi) },
            applyToProfile = { profile, value -> profile.copy(brakesCost = value.cost, brakesIntervalMi = value.interval) },
        ),
        Group(
            fields = setOf(EconomyField.FLUIDS_COST, EconomyField.FLUIDS_INTERVAL),
            readFromEconomy = { Fluids(it.fluidsCost, it.fluidsIntervalMi) },
            applyToProfile = { profile, value -> profile.copy(fluidsCost = value.cost, fluidsIntervalMi = value.interval) },
        ),
        Group(
            fields = setOf(EconomyField.MISC_YEARLY, EconomyField.MISC_YEARLY_MI),
            readFromEconomy = { Misc(it.miscYearly, it.miscYearlyMi) },
            applyToProfile = { profile, value -> profile.copy(miscYearly = value.yearly, miscYearlyMi = value.miles) },
        ),
        Group(
            fields = setOf(EconomyField.INCLUDE_DEPRECIATION, EconomyField.PURCHASE_PRICE, EconomyField.TOTAL_LIFETIME_MI),
            readFromEconomy = { Depreciation(it.includeDepreciation, it.purchasePrice, it.totalLifetimeMi) },
            applyToProfile = { profile, value -> profile.copy(includeDepreciation = value.include, purchasePrice = value.price, totalLifetimeMi = value.lifetime) },
        ),
        Group(
            fields = setOf(EconomyField.INSURANCE_DELTA),
            readFromEconomy = { Insurance(it.insuranceDeltaPerMonth) },
            applyToProfile = { profile, value -> profile.copy(insuranceDeltaPerMonth = value.value) },
        ),
        Group(
            fields = setOf(EconomyField.REGISTRATION_DELTA),
            readFromEconomy = { Registration(it.registrationDeltaPerYear) },
            applyToProfile = { profile, value -> profile.copy(registrationDeltaPerYear = value.value) },
        ),
        Group(
            fields = setOf(EconomyField.EXPECTED_ANNUAL_MI),
            readFromEconomy = { AnnualMiles(it.expectedAnnualMiles) },
            applyToProfile = { profile, value -> profile.copy(expectedAnnualMiles = value.value) },
        ),
        Group(
            fields = setOf(EconomyField.PHONE_PLAN_TOTAL, EconomyField.PHONE_PLAN_LINES, EconomyField.PHONE_BUSINESS_PERCENT),
            readFromEconomy = { Phone(it.phonePlanTotal, it.phonePlanLines, it.phoneBusinessPercent) },
        ),
        Group(
            fields = setOf(EconomyField.AVG_MIN_PER_MILE),
            readFromEconomy = { Pace(it.avgMinutesPerMile) },
        ),
        Group(
            fields = setOf(EconomyField.BASE_PICKUP_MIN),
            readFromEconomy = { Pickup(it.basePickupMinutes) },
        ),
    )
}
