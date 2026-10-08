package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.offer.OfferQuoteBasis
import kotlinx.serialization.Serializable

@Serializable

data class OfferEvaluation(
    val action: OfferAction,
    val score: Double,
    val qualityLevel: OfferQuality,
    /** Gross pay as shown on the offer screen. */
    val payAmount: Double,
    /** Estimated fuel cost for this offer's route. Zero for non-fuel vehicle classes. */
    val fuelCostEstimate: Double,
    /**
     * Estimated non-fuel operating cost for this offer's route:
     * tires + oil + brakes + fluids + misc + depreciation + insurance +
     * registration + phone. Amortized per-mile.
     */
    val nonFuelCostEstimate: Double = 0.0,
    /** [fuelCostEstimate] + [nonFuelCostEstimate]. */
    val totalOperatingCost: Double = 0.0,
    /** Total operating cost per mile from the user's economy profile. */
    val operatingCostPerMile: Double = 0.0,
    /** Net pay after all operating costs: [payAmount] - [totalOperatingCost]. */
    val netPayAmount: Double,
    val distanceMiles: Double,
    /** Net dollars per mile ([netPayAmount] / [distanceMiles]). */
    val dollarsPerMile: Double,
    /** Net implied hourly rate. */
    val dollarsPerHour: Double,
    /** Estimated time for this offer in minutes, based on [UserEconomy] constants. */
    val estimatedTimeMinutes: Double,
    val itemCount: Double,
    val merchantName: String,
    /**
     * True when the user's [UserEconomy] still has at least one field at its
     * vehicle-class / built-in default. Surfaces a "(default)" hint in the UI.
     */
    val isUsingDefaults: Boolean = false,
    /**
     * Caveat messages about unrealistic rule targets. Empty when all targets are reasonable.
     * Shown to the user when configuring rules so they understand why offers are being declined.
     */
    val warnings: List<String> = emptyList(),
    /**
     * #823 Phase 2 — the handling term of [estimatedTimeMinutes] as priced at accept time
     * (shop items ÷ pace floored at the base, plus the non-shop legs' base); the arrival
     * correction swaps THIS term, never the drive term. Nullable + default so persisted
     * evaluations and fixtures are unaffected.
     */
    val handlingMinutes: Double? = null,
    val nonShopLegs: Int = 0,
    /**
     * #823 Phase 2 — the pace and base this evaluation was priced with; the arrival correction re-prices the
     * handling term from THESE, never from a later economy, so only the item count moves.
     */
    val pricedShopItemsPerMinute: Double? = null,
    /** See [pricedShopItemsPerMinute] for the accept-time pricing contract. */
    val pricedBasePickupMinutes: Double? = null,
    val quoteBasis: OfferQuoteBasis = OfferQuoteBasis.TOTAL,
    /** Raw platform delta, never a completion estimate. */
    val incrementalMinutes: Long? = null,
) {
    /**
     * True for a TOTAL quote evaluated against a positive parsed distance. Costs, net, rates,
     * time and score may then be consumed as measurements. INCREMENTAL quotes keep real raw
     * distance but have no derived metrics; preference verdicts still apply.
     *
     * False when the offer's distance never parsed (#936). Those fields are then `0.0`
     * PLACEHOLDERS meaning *unknown*, not measurements: [netPayAmount] is gross (no cost was
     * deducted) and the verdict is [OfferQuality.UNKNOWN]/[OfferAction.NOTHING]. Any surface that
     * renders or speaks a per-mile / per-hour / distance figure must check this first and show its
     * "unknown" affordance instead — printing the zeros would quote a rate we never computed.
     * ([operatingCostPerMile] is exempt: it is the economy profile's own rate, not distance-derived.)
     */
    val hasDistanceMetrics: Boolean get() = quoteBasis == OfferQuoteBasis.TOTAL && distanceMiles > 0.0
}
