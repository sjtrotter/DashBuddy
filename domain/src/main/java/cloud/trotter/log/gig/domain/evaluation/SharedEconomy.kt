package cloud.trotter.log.gig.domain.evaluation

/** Business-wide inputs; propulsion and service assumptions live on the vehicle. */
data class SharedEconomy(
    val gasPricePerGallon: Double = UserEconomy.DEFAULT_GAS_PRICE_PER_GALLON,
    val electricityPricePerKwh: Double = DEFAULT_ELECTRICITY_PRICE_PER_KWH,
    val avgMinutesPerMile: Double? = null,
    val basePickupMinutes: Double? = null,
    val learnedShopItemsPerMinute: Double? = null,
    val shopRateSampleCount: Int = 0,
    val shopSeedItemsPerMin: Double = UserEconomy.DEFAULT_SHOP_ITEMS_PER_MIN,
    val learnedItemsPerUnitRatio: Double? = null,
    val itemsPerUnitRatioSampleCount: Int = 0,
    val itemsPerUnitRatioSeed: Double = UserEconomy.DEFAULT_ITEMS_PER_UNIT_RATIO,
    val phonePlanTotal: Double = UserEconomy.DEFAULT_PHONE_PLAN_TOTAL,
    val phonePlanLines: Int = UserEconomy.DEFAULT_PHONE_PLAN_LINES,
    val phoneBusinessPercent: Double = UserEconomy.DEFAULT_PHONE_BUSINESS_PERCENT,
    val expectedAnnualMiles: Double = UserEconomy.DEFAULT_ANNUAL_MI,
    val userSetFields: Set<EconomyField> = emptySet(),
) {
    companion object {
        const val DEFAULT_ELECTRICITY_PRICE_PER_KWH = 0.15
        fun from(economy: UserEconomy): SharedEconomy = with(economy) {
            SharedEconomy(gasPricePerGallon, electricityPricePerKwh, avgMinutesPerMile,
                basePickupMinutes, learnedShopItemsPerMinute, shopRateSampleCount, shopSeedItemsPerMin,
                learnedItemsPerUnitRatio, itemsPerUnitRatioSampleCount, itemsPerUnitRatioSeed,
                phonePlanTotal, phonePlanLines, phoneBusinessPercent, expectedAnnualMiles, userSetFields)
        }
    }
}
