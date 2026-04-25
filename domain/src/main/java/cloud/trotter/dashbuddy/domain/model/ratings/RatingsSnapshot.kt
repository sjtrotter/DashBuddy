package cloud.trotter.dashbuddy.domain.model.ratings

/**
 * A point-in-time snapshot of the dasher's performance metrics, captured when the Ratings
 * screen is observed. Stored on [AppStateV2.IdleOffline] so the bubble HUD can display it
 * on the idle card without re-observing the ratings screen.
 */
data class RatingsSnapshot(
    val capturedAt: Long = System.currentTimeMillis(),
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
