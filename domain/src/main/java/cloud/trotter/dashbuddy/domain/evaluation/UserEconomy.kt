package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass

/**
 * The persistent financial and time-estimation profile of the Dasher.
 * Assembled from stored preferences and passed into [EvaluationConfig].
 *
 * All cost components are entered in *human units* (cost per tire set + tire
 * lifetime miles, oil change cost + interval, monthly insurance, etc.) and
 * converted to per-mile internally so the evaluator can do a single
 * subtraction: `netPay = grossPay - distance * operatingCostPerMile`.
 *
 * Time-fixed costs (insurance, registration, phone) are amortized via
 * [expectedAnnualDashMiles] — the user's estimate of how many miles per year
 * they expect to dash. Fixed-cost per-mile = (annual cost) / annual dash miles.
 *
 * Defaults come from the selected [vehicleClass]'s preset values. Switching
 * class shifts any field NOT in [userSetFields] to the new class's default;
 * user-set fields are preserved.
 */
data class UserEconomy(
    val vehicleClass: VehicleClass = VehicleClass.SEDAN,
    val vehicleMpg: Double = 30.0,
    val gasPricePerGallon: Double = 3.50,

    /** Minutes per mile of driving — default 2.5 (urban average). */
    val avgMinutesPerMile: Double = DEFAULT_MINUTES_PER_MILE,
    /** Base overhead per offer (pickup + dropoff) in minutes — default 7.0. */
    val basePickupMinutes: Double = DEFAULT_BASE_PICKUP_MINUTES,

    // Maintenance (paired: cost + interval/lifetime). Defaults are zero so a
    // hand-constructed UserEconomy is cost-free for tests. Production paths
    // populate these from the VehicleClass preset via the repository.
    val tireSetCost: Double = 0.0,
    val tireLifetimeMi: Double = 1.0,
    val oilCost: Double = 0.0,
    val oilIntervalMi: Double = 1.0,
    val brakesCost: Double = 0.0,
    val brakesIntervalMi: Double = 1.0,
    val fluidsCost: Double = 0.0,
    val fluidsIntervalMi: Double = 1.0,
    val miscYearly: Double = 0.0,
    val miscYearlyMi: Double = 1.0,

    // Depreciation (gated by include-toggle; off by default so domain-only
    // construction yields a cost-free economy)
    val includeDepreciation: Boolean = false,
    val purchasePrice: Double = 0.0,
    val totalLifetimeMi: Double = 1.0,

    // Fixed costs (amortized via expectedAnnualDashMiles)
    val insuranceDeltaPerMonth: Double = 0.0,
    val registrationDeltaPerYear: Double = 0.0,
    val expectedAnnualDashMiles: Double = DEFAULT_ANNUAL_DASH_MI,

    // Phone & data (NOT driven by VehicleClass; stored separately). Domain
    // default is zero plan cost — production populates via repository.
    val phonePlanTotal: Double = 0.0,
    val phonePlanLines: Int = 1,
    val phoneDashPercent: Double = 0.0,

    /** Which [EconomyField]s the user has explicitly set. The rest are class defaults. */
    val userSetFields: Set<EconomyField> = emptySet(),
) {
    /** Marginal fuel cost per mile. Zero for vehicle classes that don't burn fuel. */
    val fuelCostPerMile: Double = if (!vehicleClass.burnsFuel) 0.0
                                  else gasPricePerGallon / vehicleMpg.coerceAtLeast(1.0)

    val tireCostPerMile: Double = tireSetCost / tireLifetimeMi.coerceAtLeast(1.0)
    val oilCostPerMile: Double = oilCost / oilIntervalMi.coerceAtLeast(1.0)
    val brakesCostPerMile: Double = brakesCost / brakesIntervalMi.coerceAtLeast(1.0)
    val fluidsCostPerMile: Double = fluidsCost / fluidsIntervalMi.coerceAtLeast(1.0)
    val miscCostPerMile: Double = miscYearly / miscYearlyMi.coerceAtLeast(1.0)
    val depreciationCostPerMile: Double =
        if (includeDepreciation) purchasePrice / totalLifetimeMi.coerceAtLeast(1.0) else 0.0
    val insuranceCostPerMile: Double =
        (insuranceDeltaPerMonth * 12.0) / expectedAnnualDashMiles.coerceAtLeast(1.0)
    val registrationCostPerMile: Double =
        registrationDeltaPerYear / expectedAnnualDashMiles.coerceAtLeast(1.0)
    val phoneCostPerMile: Double = run {
        val yourLineShare = phonePlanTotal / phonePlanLines.coerceAtLeast(1)
        val dashingMonthly = yourLineShare * (phoneDashPercent / 100.0)
        (dashingMonthly * 12.0) / expectedAnnualDashMiles.coerceAtLeast(1.0)
    }

    val nonFuelCostPerMile: Double
        get() = tireCostPerMile + oilCostPerMile + brakesCostPerMile +
            fluidsCostPerMile + miscCostPerMile + depreciationCostPerMile +
            insuranceCostPerMile + registrationCostPerMile + phoneCostPerMile

    val operatingCostPerMile: Double get() = fuelCostPerMile + nonFuelCostPerMile

    fun isUserSet(field: EconomyField): Boolean = field in userSetFields

    /** True when at least one [EconomyField] is still at its class/default value. */
    val isUsingDefaults: Boolean get() = userSetFields.size < EconomyField.entries.size

    companion object {
        const val DEFAULT_MINUTES_PER_MILE = 2.5
        const val DEFAULT_BASE_PICKUP_MINUTES = 7.0
        const val DEFAULT_ANNUAL_DASH_MI = 10_000.0
        const val DEFAULT_PHONE_PLAN_TOTAL = 80.0
        const val DEFAULT_PHONE_PLAN_LINES = 1
        const val DEFAULT_PHONE_DASH_PERCENT = 30.0
    }
}
