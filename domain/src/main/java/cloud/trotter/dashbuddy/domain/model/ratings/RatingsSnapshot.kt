package cloud.trotter.dashbuddy.domain.model.ratings

import kotlinx.serialization.Serializable

/**
 * A point-in-time snapshot of the dasher's performance metrics, captured when the Ratings
 * screen is observed. Carried in app state so the bubble HUD can display it
 * on the idle card without re-observing the ratings screen.
 */
@Serializable
data class RatingsSnapshot(
    /** Explicit (#366): callers stamp the capture instant at the edge. */
    val capturedAt: Long,
    val acceptanceRate: Double? = null,
    val completionRate: Double? = null,
    val onTimeRate: Double? = null,
    val customerRating: Double? = null,
    val deliveriesLast30Days: Int? = null,
    val lifetimeDeliveries: Int? = null,
    val originalItemsFoundRate: Double? = null,
    val totalItemsFoundRate: Double? = null,
    val substitutionIssuesRate: Double? = null,
    val itemsWithQualityIssuesRate: Double? = null,
    val itemsWrongOrMissingRate: Double? = null,
    val lifetimeShoppingOrders: Int? = null,
)
