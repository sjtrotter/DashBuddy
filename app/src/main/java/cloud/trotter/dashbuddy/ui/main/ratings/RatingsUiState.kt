package cloud.trotter.dashbuddy.ui.main.ratings

import cloud.trotter.dashbuddy.domain.model.ratings.RatingsSnapshot
import cloud.trotter.dashbuddy.domain.state.Platform

/**
 * Immutable state for the Ratings screen (#316). A pure projection of the
 * focused platform's [RatingsSnapshot] carried in `PlatformRegion.ratings` — no
 * new persistence, no re-observation. Rates are stored as the parser emits them:
 * the standing rates (acceptance/completion/on-time + shopping-quality) are
 * **percentages 0–100**; [customerRating] is a **0–5 star** value.
 *
 * Privacy: ratings are numbers only (Principle 7) — there is no store/customer
 * text on this surface.
 */
data class RatingsUiState(
    val hasData: Boolean = false,
    /** Display-only platform label (e.g. "DoorDash"); null when unknown. */
    val platformName: String? = null,
    val capturedAt: Long? = null,

    // Headline (gauges): 0–5 star + 0–100 percentages.
    val customerRating: Double? = null,
    val onTimeRate: Double? = null,
    val completionRate: Double? = null,

    // Tiles: 0–100 percentage + counts.
    val acceptanceRate: Double? = null,
    val deliveriesLast30Days: Int? = null,
    val lifetimeDeliveries: Int? = null,

    // Shopping quality (0–100 percentages + count) — only present for shop platforms.
    val originalItemsFoundRate: Double? = null,
    val totalItemsFoundRate: Double? = null,
    val substitutionIssuesRate: Double? = null,
    val itemsWithQualityIssuesRate: Double? = null,
    val itemsWrongOrMissingRate: Double? = null,
    val lifetimeShoppingOrders: Int? = null,
) {
    /** True when the snapshot carried at least one shopping-quality metric. */
    val hasShoppingQuality: Boolean
        get() = originalItemsFoundRate != null || totalItemsFoundRate != null ||
            substitutionIssuesRate != null || itemsWithQualityIssuesRate != null ||
            itemsWrongOrMissingRate != null || lifetimeShoppingOrders != null

    companion object {
        val EMPTY = RatingsUiState()

        /** Project a snapshot (may be null → empty) onto the UI state. */
        fun from(platform: Platform?, snapshot: RatingsSnapshot?): RatingsUiState {
            if (snapshot == null) return EMPTY.copy(platformName = platform?.displayName)
            return RatingsUiState(
                hasData = true,
                platformName = platform?.displayName,
                capturedAt = snapshot.capturedAt,
                customerRating = snapshot.customerRating,
                onTimeRate = snapshot.onTimeRate,
                completionRate = snapshot.completionRate,
                acceptanceRate = snapshot.acceptanceRate,
                deliveriesLast30Days = snapshot.deliveriesLast30Days,
                lifetimeDeliveries = snapshot.lifetimeDeliveries,
                originalItemsFoundRate = snapshot.originalItemsFoundRate,
                totalItemsFoundRate = snapshot.totalItemsFoundRate,
                substitutionIssuesRate = snapshot.substitutionIssuesRate,
                itemsWithQualityIssuesRate = snapshot.itemsWithQualityIssuesRate,
                itemsWrongOrMissingRate = snapshot.itemsWrongOrMissingRate,
                lifetimeShoppingOrders = snapshot.lifetimeShoppingOrders,
            )
        }
    }
}
