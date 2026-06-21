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
 * [expectedAnnualMiles] — the user's estimate of how many miles per year
 * they expect to dash. Fixed-cost per-mile = (annual cost) / annual dash miles.
 *
 * Defaults come from the selected [vehicleClass]'s preset values. Switching
 * class shifts any field NOT in [userSetFields] to the new class's default;
 * user-set fields are preserved.
 */
data class UserEconomy(
    val vehicleClass: VehicleClass = VehicleClass.SEDAN,
    /** Derives from the class (#401) — the old 30.0 literal silently
     *  duplicated SEDAN.defaultMpg. */
    val vehicleMpg: Double = vehicleClass.defaultMpg,
    val gasPricePerGallon: Double = DEFAULT_GAS_PRICE_PER_GALLON,

    /** Minutes per mile of driving — default 2.5 (urban average). */
    val avgMinutesPerMile: Double = DEFAULT_MINUTES_PER_MILE,
    /** Base overhead per offer (pickup + dropoff) in minutes — default 7.0. */
    val basePickupMinutes: Double = DEFAULT_BASE_PICKUP_MINUTES,

    /**
     * Learned overall **shopping pace** (items/minute, *effective* — measured arrive→leave, so it
     * already absorbs in-store time + checkout + ID-check + wait). Null until enough shops are
     * measured; the Shop & Deliver time estimate falls back to [DEFAULT_SHOP_ITEMS_PER_MIN].
     * Populated by the repository from a separately-persisted learned store — **NOT** user-set (a
     * vehicle-class reseed must never touch it), hence deliberately not an [EconomyField] / not in
     * [userSetFields]. #556. (Per-store / chain pace is the deferred #159 tier.)
     */
    val learnedShopItemsPerMinute: Double? = null,
    /** Number of measured shops behind [learnedShopItemsPerMinute]; gates trusting it (#556). */
    val shopRateSampleCount: Int = 0,

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

    // Fixed costs (amortized via expectedAnnualMiles)
    val insuranceDeltaPerMonth: Double = 0.0,
    val registrationDeltaPerYear: Double = 0.0,
    val expectedAnnualMiles: Double = DEFAULT_ANNUAL_MI,

    // Phone & data (NOT driven by VehicleClass; stored separately). Domain
    // default is zero plan cost — production populates via repository.
    val phonePlanTotal: Double = 0.0,
    val phonePlanLines: Int = 1,
    val phoneBusinessPercent: Double = 0.0,

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
        (insuranceDeltaPerMonth * 12.0) / expectedAnnualMiles.coerceAtLeast(1.0)
    val registrationCostPerMile: Double =
        registrationDeltaPerYear / expectedAnnualMiles.coerceAtLeast(1.0)
    val phoneCostPerMile: Double = run {
        val yourLineShare = phonePlanTotal / phonePlanLines.coerceAtLeast(1)
        val dashingMonthly = yourLineShare * (phoneBusinessPercent / 100.0)
        (dashingMonthly * 12.0) / expectedAnnualMiles.coerceAtLeast(1.0)
    }

    val nonFuelCostPerMile: Double
        get() = tireCostPerMile + oilCostPerMile + brakesCostPerMile +
            fluidsCostPerMile + miscCostPerMile + depreciationCostPerMile +
            insuranceCostPerMile + registrationCostPerMile + phoneCostPerMile

    val operatingCostPerMile: Double get() = fuelCostPerMile + nonFuelCostPerMile

    /**
     * Items/minute for the Shop & Deliver time estimate (#556): the learned overall pace once
     * [MIN_SHOP_SAMPLES] shops have been measured, else the data-derived seed. Always > 0.
     */
    val effectiveShopItemsPerMinute: Double
        get() = learnedShopItemsPerMinute
            ?.takeIf { shopRateSampleCount >= MIN_SHOP_SAMPLES && it > 0.0 }
            ?: DEFAULT_SHOP_ITEMS_PER_MIN

    fun isUserSet(field: EconomyField): Boolean = field in userSetFields

    /** True when at least one [EconomyField] is still at its class/default value. */
    val isUsingDefaults: Boolean get() = userSetFields.size < EconomyField.entries.size

    companion object {
        /** Fallback price-per-gallon when no fetched price exists (#401). */
        const val DEFAULT_GAS_PRICE_PER_GALLON = 3.50

        const val DEFAULT_MINUTES_PER_MILE = 2.5
        const val DEFAULT_BASE_PICKUP_MINUTES = 7.0

        /**
         * Effective shopping pace (items/min, incl. checkout/ID/wait), the cold-start seed for the
         * Shop & Deliver time estimate. Derived from 48 real shops across the 2026-06 capture corpus
         * (median 0.79, IQR 0.66–0.92). The old flat 7-min handling estimated a 25-item shop at
         * ~15 min → the ~$116/hr bug (#556); 0.8/min puts it at ~31 min in-store. */
        const val DEFAULT_SHOP_ITEMS_PER_MIN = 0.8

        /** Measured shops required before the learned pace overrides [DEFAULT_SHOP_ITEMS_PER_MIN]. */
        const val MIN_SHOP_SAMPLES = 5
        const val DEFAULT_ANNUAL_MI = 10_000.0
        const val DEFAULT_PHONE_PLAN_TOTAL = 80.0
        const val DEFAULT_PHONE_PLAN_LINES = 1
        const val DEFAULT_PHONE_BUSINESS_PERCENT = 30.0
    }
}
