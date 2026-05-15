package cloud.trotter.dashbuddy.ui.main.setup.wizard.model

import cloud.trotter.dashbuddy.domain.config.DashStrategy
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
    val expectedAnnualDashMiles: Double = UserEconomy.DEFAULT_ANNUAL_DASH_MI,
    val phonePlanTotal: Double = UserEconomy.DEFAULT_PHONE_PLAN_TOTAL,
    val phonePlanLines: Int = UserEconomy.DEFAULT_PHONE_PLAN_LINES,
    val phoneDashPercent: Double = UserEconomy.DEFAULT_PHONE_DASH_PERCENT,
    val avgMinutesPerMile: Double = UserEconomy.DEFAULT_MINUTES_PER_MILE,
    val basePickupMinutes: Double = UserEconomy.DEFAULT_BASE_PICKUP_MINUTES,
    val userSetEconomyFields: Set<EconomyField> = emptySet(),

    // Strategy Variables
    val strategy: DashStrategy = DashStrategy.MANUAL,
    val allowShopping: Boolean = true,

    // Targets & Enforcement
    val enforceMinPayout: Boolean = false,
    val minPayout: Float = 5.0f,

    val enforceTargetHourly: Boolean = false,
    val targetHourly: Float = 20.0f,

    val enforceMaxDistance: Boolean = false,
    val maxDistance: Float = 10.0f,

    val maxItems: Float = 15.0f // Shopping
)