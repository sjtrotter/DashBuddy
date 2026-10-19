package cloud.trotter.dashbuddy.feature.setup.wizard.model

import cloud.trotter.dashbuddy.domain.config.OfferStrategy
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.domain.evaluation.*

/**
 * Represents the volatile UI state of the Setup Wizard before it is committed to DataStore.
 */
data class WizardState(
    val vehicleProfile: VehicleProfile = GasCar(),
    val kwhPer100Mi: Double = VehiclePresets.EV_KWH_PER_100_MI,
    val whPerMile: Double = VehiclePresets.EBIKE_WH_PER_MILE,
    val electricityPricePerKwh: Double = SharedEconomy.DEFAULT_ELECTRICITY_PRICE_PER_KWH,
    val chainCost: Double = 0.0, val chainLifeMi: Double = 1.0,
    val batteryCost: Double = 0.0, val batteryLifeMi: Double = 1.0,
    val beltCost: Double = 0.0, val beltLifeMi: Double = 1.0,
    // Economy Variables
    val vehicleClass: VehicleClass = VehicleClass.SEDAN,
    val vehicleLookupAllowed: Boolean = false,
    // UI-only: showing the MPG control must not alter the vehicle identity.
    val manualMpgEntry: Boolean = false,
    val vehicleYear: String = "",
    val vehicleMake: String = "",
    val vehicleModel: String = "",
    val vehicleTrim: String = "",
    val estimatedMpg: Float = 25.0f,

    // Gas Variables
    val fuelType: FuelType = FuelType.REGULAR,
    val isGasPriceAuto: Boolean = false,
    val gasPrice: Float = 3.50f,
    val isFetchingGasPrice: Boolean = false,
    val fetchedGasPrices: Map<FuelType, Float> = emptyMap(),

    // Personal Economy v2 — operating cost components (#145)
    val tireSetCost: Double = VehicleClass.SEDAN.tireSetCost,
    val tireLifetimeMi: Double = VehicleClass.SEDAN.tireLifetimeMi,
    val oilCost: Double = VehicleClass.SEDAN.oilCost,
    val oilIntervalMi: Double = VehicleClass.SEDAN.oilIntervalMi,
    val brakesCost: Double = VehicleClass.SEDAN.brakesCost,
    val brakesIntervalMi: Double = VehicleClass.SEDAN.brakesIntervalMi,
    val fluidsCost: Double = VehicleClass.SEDAN.fluidsCost,
    val fluidsIntervalMi: Double = VehicleClass.SEDAN.fluidsIntervalMi,
    val miscYearly: Double = VehicleClass.SEDAN.miscYearly,
    val miscYearlyMi: Double = VehicleClass.SEDAN.miscYearlyMi,
    val includeDepreciation: Boolean = true,
    val purchasePrice: Double = VehicleClass.SEDAN.purchasePrice,
    val totalLifetimeMi: Double = VehicleClass.SEDAN.totalLifetimeMi,
    val insuranceDeltaPerMonth: Double = 0.0,
    val registrationDeltaPerYear: Double = 0.0,
    val expectedAnnualMiles: Double = UserEconomy.DEFAULT_ANNUAL_MI,
    val phonePlanTotal: Double = UserEconomy.DEFAULT_PHONE_PLAN_TOTAL,
    val phonePlanLines: Int = UserEconomy.DEFAULT_PHONE_PLAN_LINES,
    val phoneBusinessPercent: Double = UserEconomy.DEFAULT_PHONE_BUSINESS_PERCENT,
    val avgMinutesPerMile: Double = UserEconomy.DEFAULT_MINUTES_PER_MILE,
    val basePickupMinutes: Double = UserEconomy.DEFAULT_BASE_PICKUP_MINUTES,
    val userSetEconomyFields: Set<EconomyField> = emptySet(),

    // Strategy Variables
    val strategy: OfferStrategy = OfferStrategy.MANUAL,

    // Targets & Enforcement
    val enforceMinPayout: Boolean = false,
    val minPayout: Float = 5.0f,

    val enforceTargetHourly: Boolean = false,
    val targetHourly: Float = 20.0f,

    val enforceMaxDistance: Boolean = false,
    val maxDistance: Float = 10.0f,

    val maxItems: Float = 15.0f // Shopping
)

/**
 * Projects the wizard's in-memory economy edits onto a [UserEconomy] — the single
 * owner of this conversion (#357). The ECONOMY_COSTS card uses it to drive live
 * $/mi summaries and "(default)" badges; [cloud.trotter.dashbuddy.feature.setup.wizard.WizardViewModel.saveAndFinish]
 * commits its vehicle overrides in one targeted profile write and mirrors the
 * user-set fields to legacy preferences. Carries
 * [userSetEconomyFields] so the repository persists exactly the fields the user touched.
 */
private fun WizardState.draftEconomy(): UserEconomy = UserEconomy(
    vehicleClass = vehicleClass,
    kwhPer100Mi = kwhPer100Mi, whPerMile = whPerMile, electricityPricePerKwh = electricityPricePerKwh,
    chainCost = chainCost, chainLifeMi = chainLifeMi, batteryCost = batteryCost, batteryLifeMi = batteryLifeMi,
    beltCost = beltCost, beltLifeMi = beltLifeMi,
    vehicleMpg = estimatedMpg.toDouble(),
    gasPricePerGallon = gasPrice.toDouble(),
    avgMinutesPerMile = avgMinutesPerMile,
    basePickupMinutes = basePickupMinutes,
    tireSetCost = tireSetCost, tireLifetimeMi = tireLifetimeMi,
    oilCost = oilCost, oilIntervalMi = oilIntervalMi,
    brakesCost = brakesCost, brakesIntervalMi = brakesIntervalMi,
    fluidsCost = fluidsCost, fluidsIntervalMi = fluidsIntervalMi,
    miscYearly = miscYearly, miscYearlyMi = miscYearlyMi,
    includeDepreciation = includeDepreciation,
    purchasePrice = purchasePrice, totalLifetimeMi = totalLifetimeMi,
    insuranceDeltaPerMonth = insuranceDeltaPerMonth,
    registrationDeltaPerYear = registrationDeltaPerYear,
    expectedAnnualMiles = expectedAnnualMiles,
    phonePlanTotal = phonePlanTotal,
    phonePlanLines = phonePlanLines,
    phoneBusinessPercent = phoneBusinessPercent,
    userSetFields = userSetEconomyFields,
)

fun WizardState.toVehicleProfile(): VehicleProfile {
    val edited = EconomyFieldGroups.updateProfile(vehicleProfile, draftEconomy())
    return if (edited.hasCarIdentity) edited.withIdentity(
        vehicleYear.takeIf { it.isNotBlank() }, vehicleMake.takeIf { it.isNotBlank() }, vehicleModel.takeIf { it.isNotBlank() }) else edited
}

fun WizardState.toUserEconomy(): UserEconomy {
    val shared = SharedEconomy.from(draftEconomy())
    return UserEconomy.from(VehicleEconomyInputs.from(toVehicleProfile(), shared), shared)
}
