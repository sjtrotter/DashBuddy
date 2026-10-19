package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.vehicle.*

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
    /**
     * Base overhead per ORDER (one pickup + one dropoff) in minutes — default 7.0.
     * A stacked offer is charged once per order (#1113); a Shop & Deliver order's shop time
     * replaces it (#556).
     */
    val basePickupMinutes: Double = DEFAULT_BASE_PICKUP_MINUTES,
    /** Raw read-model medians, kept apart from overrides and vehicle defaults (#254). */
    val learnedMinutesPerMile: Double? = null,
    val learnedStopOverheadMinutes: Double? = null,
    val timeConstantSampleCount: Int = 0,

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

    /**
     * The cold-start seed pace (items/min) for the offer's platform (#588), used until this platform
     * has its own [MIN_SHOP_SAMPLES] measured shops. Resolved from [ShopRateSeeds] by
     * [EvaluationConfig.forPlatform] at eval time; defaults to the generic corpus median off-config so
     * a hand-built economy (and every existing test) behaves exactly as before. Keeps the seed
     * per-platform without widening [OfferEvaluator]'s signature.
     */
    val shopSeedItemsPerMin: Double = DEFAULT_SHOP_ITEMS_PER_MIN,

    /**
     * Learned **items:units ratio** (#823 Phase 1) — how many physical items one DoorDash "unit" is
     * worth, so a units-only offer (`(64 units)`) can be converted to an items-equivalent before the
     * #556 shop-time pace divide. Null until enough units-shops are measured; the conversion falls
     * back to [itemsPerUnitRatioSeed]. Like [learnedShopItemsPerMinute] this is a MEASURED value kept
     * in the strategy store (never a user-set economy field, so a vehicle-class reseed can't touch
     * it). Resolved per-platform by [EvaluationConfig.forPlatform].
     */
    val learnedItemsPerUnitRatio: Double? = null,
    /** Number of measured units-shops behind [learnedItemsPerUnitRatio]; gates trusting it (#823). */
    val itemsPerUnitRatioSampleCount: Int = 0,
    /**
     * The cold-start seed items:units ratio for the offer's platform (#823), used until this platform
     * has its own [ItemsPerUnitRatio.MIN_SAMPLES] measured units-shops. Resolved from
     * [ItemsPerUnitRatioSeeds] by [EvaluationConfig.forPlatform] at eval time; defaults to the generic
     * corpus seed off-config so a hand-built economy (and every existing test) behaves as before.
     */
    val itemsPerUnitRatioSeed: Double = DEFAULT_ITEMS_PER_UNIT_RATIO,

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
    val electricityPricePerKwh: Double = SharedEconomy.DEFAULT_ELECTRICITY_PRICE_PER_KWH,
    val chainCost: Double = 0.0, val chainLifeMi: Double = 1.0,
    val batteryCost: Double = 0.0, val batteryLifeMi: Double = 1.0,
    val beltCost: Double = 0.0, val beltLifeMi: Double = 1.0,
    val kwhPer100Mi: Double = VehiclePresets.EV_KWH_PER_100_MI,
    val whPerMile: Double = VehiclePresets.EBIKE_WH_PER_MILE,
    val activeVehicleId: String? = null,
    val vehicleInputs: VehicleEconomyInputs? = null,

) {
    /** Marginal fuel cost per mile. Zero for vehicle classes that don't burn fuel. */
    val fuelCostPerMile: Double = vehicleInputs?.energyPerMile ?: if (!vehicleClass.burnsFuel) 0.0
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

    val chainCostPerMile: Double = chainCost / chainLifeMi.coerceAtLeast(1.0)
    val batteryCostPerMile: Double = batteryCost / batteryLifeMi.coerceAtLeast(1.0)
    val beltCostPerMile: Double = beltCost / beltLifeMi.coerceAtLeast(1.0)
    val vehicleProfile: VehicleProfile get() = vehicleInputs?.disclosure?.profile ?: LegacyProfile(vehicleClass = vehicleClass).toTyped()

    val nonFuelCostPerMile: Double
        get() = vehicleInputs?.let { it.wearPerMile + it.depreciationPerMile + it.fixedPerMile + phoneCostPerMile }
            ?: tireCostPerMile + oilCostPerMile + brakesCostPerMile +
            fluidsCostPerMile + miscCostPerMile + chainCostPerMile + batteryCostPerMile + beltCostPerMile + depreciationCostPerMile +
            insuranceCostPerMile + registrationCostPerMile + phoneCostPerMile

    val operatingCostPerMile: Double get() = fuelCostPerMile + nonFuelCostPerMile

    /**
     * Items/minute for the Shop & Deliver time estimate (#556): the learned pace once
     * [MIN_SHOP_SAMPLES] shops have been measured for this platform, else this platform's
     * [shopSeedItemsPerMin] seed (#588). Always > 0.
     */
    val effectiveShopItemsPerMinute: Double
        get() = learnedShopItemsPerMinute
            ?.takeIf { shopRateSampleCount >= MIN_SHOP_SAMPLES && it > 0.0 }
            ?: shopSeedItemsPerMin

    /**
     * Items:units ratio for the units→items-equivalent conversion (#823 Phase 1): the learned ratio
     * once [ItemsPerUnitRatio.MIN_SAMPLES] units-shops have been measured for this platform (and it
     * is in the sane [ItemsPerUnitRatio.MIN_RATIO]..[ItemsPerUnitRatio.MAX_RATIO] band), else this
     * platform's [itemsPerUnitRatioSeed]. Always > 0.
     */
    val effectiveItemsPerUnitRatio: Double
        get() = learnedItemsPerUnitRatio
            ?.takeIf {
                itemsPerUnitRatioSampleCount >= ItemsPerUnitRatio.MIN_SAMPLES &&
                    it in ItemsPerUnitRatio.MIN_RATIO..ItemsPerUnitRatio.MAX_RATIO
            }
            ?: itemsPerUnitRatioSeed

    private val automaticTimeConstants: TimeConstantPair
        get() = TimeConstants.blend(
            TimeConstantPair(avgMinutesPerMile, basePickupMinutes),
            learnedMinutesPerMile?.let { pace ->
                learnedStopOverheadMinutes?.let { overhead ->
                    LearnedTimeConstants(TimeConstantPair(pace, overhead), timeConstantSampleCount)
                }
            },
        )

    val effectiveAvgMinutesPerMile: Double
        get() = if (isUserSet(EconomyField.AVG_MIN_PER_MILE)) avgMinutesPerMile
            else automaticTimeConstants.minutesPerMile

    val effectiveBasePickupMinutes: Double
        get() = if (isUserSet(EconomyField.BASE_PICKUP_MIN)) basePickupMinutes
            else automaticTimeConstants.stopOverheadMinutes

    fun isUserSet(field: EconomyField): Boolean = field in userSetFields

    /** True when at least one [EconomyField] is still at its class/default value. */
    val isUsingDefaults: Boolean get() = !userSetFields.containsAll(EconomyFieldGroups.fieldsFor(vehicleProfile))

    companion object {
        fun from(inputs: VehicleEconomyInputs, shared: SharedEconomy): UserEconomy {
            val detail = inputs.disclosure
            val profile = detail?.profile ?: GasCar()
            val wear = detail?.wear ?: WearProfile(miscYearly = inputs.wearPerMile, miscYearlyMi = 1.0)
            val fixed = detail?.fixed ?: FixedCosts()
            val fields = EconomyFieldGroups.userSetFields(profile) +
                shared.userSetFields.filter { it !in EconomyFieldGroups.vehicleFields }
            return UserEconomy(
                vehicleClass = profile.legacyClass,
                vehicleMpg = profile.gasMpg?.takeIf { it > 0 } ?: VehiclePresets.forProfile(profile).mpg,
                gasPricePerGallon = shared.gasPricePerGallon,
                kwhPer100Mi = when (profile) {
                    is ElectricCar -> profile.kwhPer100Mi ?: VehiclePresets.EV_KWH_PER_100_MI
                    is ScooterMoped -> profile.kwhPer100Mi ?: VehiclePresets.SCOOTER_KWH_PER_100_MI
                    else -> 0.0
                },
                whPerMile = (profile as? EBike)?.whPerMile ?: VehiclePresets.EBIKE_WH_PER_MILE,
                electricityPricePerKwh = shared.electricityPricePerKwh,
                avgMinutesPerMile = if (EconomyField.AVG_MIN_PER_MILE in fields)
                    shared.avgMinutesPerMile ?: inputs.travel.minutesPerMile else inputs.travel.minutesPerMile,
                basePickupMinutes = if (EconomyField.BASE_PICKUP_MIN in fields)
                    shared.basePickupMinutes ?: inputs.travel.basePickupMinutes else inputs.travel.basePickupMinutes,
                learnedShopItemsPerMinute = shared.learnedShopItemsPerMinute,
                shopRateSampleCount = shared.shopRateSampleCount,
                shopSeedItemsPerMin = shared.shopSeedItemsPerMin,
                learnedItemsPerUnitRatio = shared.learnedItemsPerUnitRatio,
                itemsPerUnitRatioSampleCount = shared.itemsPerUnitRatioSampleCount,
                itemsPerUnitRatioSeed = shared.itemsPerUnitRatioSeed,
                tireSetCost = wear.tireSetCost ?: 0.0,
                tireLifetimeMi = wear.tireLifetimeMi ?: 1.0,
                oilCost = wear.oilCost ?: 0.0,
                oilIntervalMi = wear.oilIntervalMi ?: 1.0,
                brakesCost = wear.brakesCost ?: 0.0,
                brakesIntervalMi = wear.brakesIntervalMi ?: 1.0,
                fluidsCost = wear.fluidsCost ?: 0.0,
                fluidsIntervalMi = wear.fluidsIntervalMi ?: 1.0,
                chainCost = wear.chainCost ?: 0.0,
                chainLifeMi = wear.chainLifeMi ?: 1.0,
                batteryCost = wear.batteryCost ?: 0.0,
                batteryLifeMi = wear.batteryLifeMi ?: 1.0,
                beltCost = wear.beltCost ?: 0.0,
                beltLifeMi = wear.beltLifeMi ?: 1.0,
                miscYearly = wear.miscYearly ?: 0.0,
                miscYearlyMi = wear.miscYearlyMi ?: 1.0,
                includeDepreciation = fixed.includeDepreciation ?: false,
                purchasePrice = fixed.purchasePrice ?: 0.0,
                totalLifetimeMi = fixed.totalLifetimeMi ?: 1.0,
                insuranceDeltaPerMonth = fixed.insuranceDeltaPerMonth ?: 0.0,
                registrationDeltaPerYear = fixed.registrationDeltaPerYear ?: 0.0,
                expectedAnnualMiles = fixed.expectedAnnualMiles ?: shared.expectedAnnualMiles,
                phonePlanTotal = shared.phonePlanTotal, phonePlanLines = shared.phonePlanLines,
                phoneBusinessPercent = shared.phoneBusinessPercent,
                userSetFields = fields, vehicleInputs = inputs,
            )
        }


        /** Fallback price-per-gallon when no fetched price exists (#401). */
        const val DEFAULT_GAS_PRICE_PER_GALLON = 3.50

        const val DEFAULT_MINUTES_PER_MILE = VehiclePresets.CAR_MINUTES_PER_MILE
        const val DEFAULT_BASE_PICKUP_MINUTES = VehiclePresets.CAR_BASE_PICKUP_MINUTES

        /**
         * Effective shopping pace (items/min, incl. checkout/ID/wait), the cold-start seed for the
         * Shop & Deliver time estimate. Derived from 48 real shops across the 2026-06 capture corpus
         * (median 0.79, IQR 0.66–0.92). The old flat 7-min handling estimated a 25-item shop at
         * ~15 min → the ~$116/hr bug (#556); 0.8/min puts it at ~31 min in-store. */
        const val DEFAULT_SHOP_ITEMS_PER_MIN = 0.8

        /** Measured shops required before the learned pace overrides [DEFAULT_SHOP_ITEMS_PER_MIN]. */
        const val MIN_SHOP_SAMPLES = 5

        /**
         * Cold-start items:units ratio seed (#823 Phase 1) — how many physical items one DoorDash
         * "unit" is worth. The domain default; production resolves the per-platform seed via
         * [ItemsPerUnitRatioSeeds]. See that object for the corpus derivation (≈ 0.75–0.81 → 0.78).
         */
        const val DEFAULT_ITEMS_PER_UNIT_RATIO = ItemsPerUnitRatioSeeds.DOORDASH_CORPUS_SEED
        const val DEFAULT_ANNUAL_MI = 10_000.0
        const val DEFAULT_PHONE_PLAN_TOTAL = 80.0
        const val DEFAULT_PHONE_PLAN_LINES = 1
        const val DEFAULT_PHONE_BUSINESS_PERCENT = 30.0
    }
}
