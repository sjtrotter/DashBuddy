package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.vehicle.*

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
    data class Consumption(val value: Double, val wh: Boolean) : Write
    data class Tariff(val value: Double) : Write
    data class Chain(val cost: Double, val life: Double) : Write
    data class Battery(val cost: Double, val life: Double) : Write
    data class Belt(val cost: Double, val life: Double) : Write
    data class Pace(val value: Double) : Write
    data class Pickup(val value: Double) : Write

    // Identity, MPG and pump price have dedicated save paths. Shared inputs leave
    // profiles unchanged; only the vehicle cost groups apply draft overrides here.
    val entries: List<Group<out Write>> = listOf(
        Group(
            fields = setOf(EconomyField.VEHICLE_CLASS),
            readFromEconomy = { VehicleClassValue(it.vehicleClass) },
            applyToProfile = { profile, value -> when (profile) {
                is GasCar -> GasCarClass.entries.firstOrNull { it.name == value.value.name }?.let { profile.copy(vehicleClass = it) } ?: profile
                is HybridCar -> GasCarClass.entries.firstOrNull { it.name == value.value.name }?.let { profile.copy(vehicleClass = it) } ?: profile
                else -> profile
            } },
        ),
        Group(
            fields = setOf(EconomyField.VEHICLE_MPG),
            readFromEconomy = { Mpg(it.vehicleMpg) },
            applyToProfile = { profile, value -> profile.withGas(mpg = value.value) },
        ),
        Group(
            fields = setOf(EconomyField.GAS_PRICE),
            readFromEconomy = { GasPrice(it.gasPricePerGallon) },
        ),
        Group(
            fields = setOf(EconomyField.KWH_PER_100_MI),
            readFromEconomy = { Consumption(it.kwhPer100Mi, false) },
            applyToProfile = { profile, value -> when (profile) {
                is ElectricCar -> profile.copy(kwhPer100Mi = value.value)
                is ScooterMoped -> if (profile.powertrain == Powertrain.ELECTRIC) profile.copy(kwhPer100Mi = value.value) else profile
                else -> profile
            } },
        ),
        Group(
            fields = setOf(EconomyField.WH_PER_MILE),
            readFromEconomy = { Consumption(it.whPerMile, true) },
            applyToProfile = { profile, value -> if (profile is EBike) profile.copy(whPerMile = value.value) else profile },
        ),
        Group(fields = setOf(EconomyField.ELECTRICITY_PRICE), readFromEconomy = { Tariff(it.electricityPricePerKwh) }),
        Group(fields = setOf(EconomyField.CHAIN_COST, EconomyField.CHAIN_LIFETIME),
            readFromEconomy = { Chain(it.chainCost, it.chainLifeMi) },
            applyToProfile = { profile, value -> profile.withCosts(wear = profile.wear.copy(chainCost = value.cost, chainLifeMi = value.life)) }),
        Group(fields = setOf(EconomyField.BATTERY_COST, EconomyField.BATTERY_LIFETIME),
            readFromEconomy = { Battery(it.batteryCost, it.batteryLifeMi) },
            applyToProfile = { profile, value -> profile.withCosts(wear = profile.wear.copy(batteryCost = value.cost, batteryLifeMi = value.life)) }),
        Group(fields = setOf(EconomyField.BELT_COST, EconomyField.BELT_LIFETIME),
            readFromEconomy = { Belt(it.beltCost, it.beltLifeMi) },
            applyToProfile = { profile, value -> profile.withCosts(wear = profile.wear.copy(beltCost = value.cost, beltLifeMi = value.life)) }),
        Group(
            fields = setOf(EconomyField.TIRE_COST, EconomyField.TIRE_LIFETIME),
            readFromEconomy = { Tires(it.tireSetCost, it.tireLifetimeMi) },
            applyToProfile = { profile, value -> profile.withCosts(wear = profile.wear.copy(tireSetCost = value.cost, tireLifetimeMi = value.lifetime)) },
        ),
        Group(
            fields = setOf(EconomyField.OIL_COST, EconomyField.OIL_INTERVAL),
            readFromEconomy = { Oil(it.oilCost, it.oilIntervalMi) },
            applyToProfile = { profile, value -> profile.withCosts(wear = profile.wear.copy(oilCost = value.cost, oilIntervalMi = value.interval)) },
        ),
        Group(
            fields = setOf(EconomyField.BRAKES_COST, EconomyField.BRAKES_INTERVAL),
            readFromEconomy = { Brakes(it.brakesCost, it.brakesIntervalMi) },
            applyToProfile = { profile, value -> profile.withCosts(wear = profile.wear.copy(brakesCost = value.cost, brakesIntervalMi = value.interval)) },
        ),
        Group(
            fields = setOf(EconomyField.FLUIDS_COST, EconomyField.FLUIDS_INTERVAL),
            readFromEconomy = { Fluids(it.fluidsCost, it.fluidsIntervalMi) },
            applyToProfile = { profile, value -> profile.withCosts(wear = profile.wear.copy(fluidsCost = value.cost, fluidsIntervalMi = value.interval)) },
        ),
        Group(
            fields = setOf(EconomyField.MISC_YEARLY, EconomyField.MISC_YEARLY_MI),
            readFromEconomy = { Misc(it.miscYearly, it.miscYearlyMi) },
            applyToProfile = { profile, value -> profile.withCosts(wear = profile.wear.copy(miscYearly = value.yearly, miscYearlyMi = value.miles)) },
        ),
        Group(
            fields = setOf(EconomyField.INCLUDE_DEPRECIATION, EconomyField.PURCHASE_PRICE, EconomyField.TOTAL_LIFETIME_MI),
            readFromEconomy = { Depreciation(it.includeDepreciation, it.purchasePrice, it.totalLifetimeMi) },
            applyToProfile = { profile, value -> profile.withCosts(fixed = profile.fixed.copy(includeDepreciation = value.include, purchasePrice = value.price, totalLifetimeMi = value.lifetime)) },
        ),
        Group(
            fields = setOf(EconomyField.INSURANCE_DELTA),
            readFromEconomy = { Insurance(it.insuranceDeltaPerMonth) },
            applyToProfile = { profile, value -> profile.withCosts(fixed = profile.fixed.copy(insuranceDeltaPerMonth = value.value)) },
        ),
        Group(
            fields = setOf(EconomyField.REGISTRATION_DELTA),
            readFromEconomy = { Registration(it.registrationDeltaPerYear) },
            applyToProfile = { profile, value -> profile.withCosts(fixed = profile.fixed.copy(registrationDeltaPerYear = value.value)) },
        ),
        Group(
            fields = setOf(EconomyField.EXPECTED_ANNUAL_MI),
            readFromEconomy = { AnnualMiles(it.expectedAnnualMiles) },
            applyToProfile = { profile, value -> profile.withCosts(fixed = profile.fixed.copy(expectedAnnualMiles = value.value)) },
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

    private val sharedFields = setOf(EconomyField.GAS_PRICE, EconomyField.ELECTRICITY_PRICE,
        EconomyField.AVG_MIN_PER_MILE, EconomyField.BASE_PICKUP_MIN, EconomyField.PHONE_PLAN_TOTAL,
        EconomyField.PHONE_PLAN_LINES, EconomyField.PHONE_BUSINESS_PERCENT)
    val vehicleFields: Set<EconomyField> = EconomyField.entries.toSet() - sharedFields

    /** The field table owns visibility, applicability and atomic writes for both editor hosts. */
    fun fieldsFor(profile: VehicleProfile): Set<EconomyField> {
        val common = setOf(EconomyField.TIRE_COST, EconomyField.TIRE_LIFETIME, EconomyField.BRAKES_COST,
            EconomyField.BRAKES_INTERVAL, EconomyField.INCLUDE_DEPRECIATION, EconomyField.PURCHASE_PRICE,
            EconomyField.TOTAL_LIFETIME_MI, EconomyField.INSURANCE_DELTA, EconomyField.REGISTRATION_DELTA,
            EconomyField.EXPECTED_ANNUAL_MI, EconomyField.AVG_MIN_PER_MILE, EconomyField.BASE_PICKUP_MIN,
            EconomyField.PHONE_PLAN_TOTAL, EconomyField.PHONE_PLAN_LINES, EconomyField.PHONE_BUSINESS_PERCENT)
        val misc = setOf(EconomyField.MISC_YEARLY, EconomyField.MISC_YEARLY_MI)
        val gas = setOf(EconomyField.VEHICLE_MPG, EconomyField.GAS_PRICE)
        val oil = setOf(EconomyField.OIL_COST, EconomyField.OIL_INTERVAL)
        val fluids = setOf(EconomyField.FLUIDS_COST, EconomyField.FLUIDS_INTERVAL)
        val chain = setOf(EconomyField.CHAIN_COST, EconomyField.CHAIN_LIFETIME)
        val electric = setOf(EconomyField.KWH_PER_100_MI, EconomyField.ELECTRICITY_PRICE)
        return common + when (profile) {
            is GasCar, is HybridCar -> gas + oil + fluids + misc + EconomyField.VEHICLE_CLASS
            is ElectricCar -> electric + fluids + misc
            is Motorcycle -> gas + oil + fluids + misc + chain
            is ScooterMoped -> (if (profile.powertrain == Powertrain.GAS) gas else electric) +
                setOf(EconomyField.BELT_COST, EconomyField.BELT_LIFETIME)
            is EBike -> chain + misc + setOf(EconomyField.WH_PER_MILE, EconomyField.ELECTRICITY_PRICE,
                EconomyField.BATTERY_COST, EconomyField.BATTERY_LIFETIME)
            is Bicycle -> chain + misc
        }
    }

    fun updateProfile(profile: VehicleProfile, economy: UserEconomy): VehicleProfile = entries
        .filter { group -> group.fields.any { it in economy.userSetFields && it in fieldsFor(profile) } }
        .fold(profile) { result, group -> group.updateProfile(result, economy) }

    fun hasCostOverrides(economy: UserEconomy): Boolean = economy.userSetFields.any {
        it in vehicleFields && it != EconomyField.VEHICLE_CLASS && it != EconomyField.VEHICLE_MPG
    }

    fun userSetFields(profile: VehicleProfile): Set<EconomyField> {
        val values: Map<EconomyField, Any?> = mapOf(

            EconomyField.TIRE_COST to profile.wear.tireSetCost,
            EconomyField.TIRE_LIFETIME to profile.wear.tireLifetimeMi,
            EconomyField.OIL_COST to profile.wear.oilCost,
            EconomyField.OIL_INTERVAL to profile.wear.oilIntervalMi,
            EconomyField.BRAKES_COST to profile.wear.brakesCost,
            EconomyField.BRAKES_INTERVAL to profile.wear.brakesIntervalMi,
            EconomyField.FLUIDS_COST to profile.wear.fluidsCost,
            EconomyField.FLUIDS_INTERVAL to profile.wear.fluidsIntervalMi,
            EconomyField.MISC_YEARLY to profile.wear.miscYearly,
            EconomyField.MISC_YEARLY_MI to profile.wear.miscYearlyMi,
            EconomyField.CHAIN_COST to profile.wear.chainCost,
            EconomyField.CHAIN_LIFETIME to profile.wear.chainLifeMi,
            EconomyField.BATTERY_COST to profile.wear.batteryCost,
            EconomyField.BATTERY_LIFETIME to profile.wear.batteryLifeMi,
            EconomyField.BELT_COST to profile.wear.beltCost,
            EconomyField.BELT_LIFETIME to profile.wear.beltLifeMi,
            EconomyField.INCLUDE_DEPRECIATION to profile.fixed.includeDepreciation,
            EconomyField.PURCHASE_PRICE to profile.fixed.purchasePrice,
            EconomyField.TOTAL_LIFETIME_MI to profile.fixed.totalLifetimeMi,
            EconomyField.INSURANCE_DELTA to profile.fixed.insuranceDeltaPerMonth,
            EconomyField.REGISTRATION_DELTA to profile.fixed.registrationDeltaPerYear,
            EconomyField.EXPECTED_ANNUAL_MI to profile.fixed.expectedAnnualMiles,
            EconomyField.VEHICLE_CLASS to profile.legacyClass.takeIf { it != VehicleClass.SEDAN },
            EconomyField.VEHICLE_MPG to profile.gasMpg?.takeIf { it > 0 },
            EconomyField.KWH_PER_100_MI to when (profile) {
                is ElectricCar -> profile.kwhPer100Mi
                is ScooterMoped -> profile.kwhPer100Mi
                else -> null
            },
            EconomyField.WH_PER_MILE to (profile as? EBike)?.whPerMile,
        )
        return values.filter { (field, value) -> value != null && field in fieldsFor(profile) }.keys
    }
}
