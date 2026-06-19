package cloud.trotter.dashbuddy.ui.main.setup.wizard.model

import cloud.trotter.dashbuddy.domain.config.OfferStrategy
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass

/**
 * Represents the volatile UI state of the Setup Wizard before it is committed to DataStore.
 */
data class WizardState(
    // Economy Variables
    val vehicleClass: VehicleClass = VehicleClass.SEDAN,
    val vehicleYear: String = "",
    val vehicleMake: String = "",
    val vehicleModel: String = "",
    val vehicleTrim: String = "",
    val estimatedMpg: Float = 25.0f,

    // Gas Variables
    val fuelType: FuelType = FuelType.REGULAR,
    val isGasPriceAuto: Boolean = true,
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
 * $/mi summaries and "(default)" badges; [cloud.trotter.dashbuddy.ui.main.setup.wizard.WizardViewModel.saveAndFinish]
 * hands it to `AppPreferencesRepository.persistUserSetEconomy`, which persists each
 * user-set field through the same atomic write the settings screen uses. Carries
 * [userSetEconomyFields] so the repository persists exactly the fields the user touched.
 */
fun WizardState.toUserEconomy(): UserEconomy = UserEconomy(
    vehicleClass = vehicleClass,
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