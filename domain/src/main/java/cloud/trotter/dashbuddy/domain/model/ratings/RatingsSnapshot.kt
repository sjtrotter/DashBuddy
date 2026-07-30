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
    /**
     * Headline points score on DoorDash's points-based rating redesign (#962).
     * A recorded FACT, nothing more: no thresholds, no tier progression and no
     * rewards modelling live in the app (dev ruling 2026-07-30) — the tier ladder
     * is one platform's own gamification, and the sovereignty-relevant question
     * ("did my $/hr move after the tier changed?") only needs the number.
     */
    val overallRatingPoints: Int? = null,
    /** The reward tier exactly as the platform labels it, e.g. "Silver" (#962). */
    val tierLabel: String? = null,
    /** Rating factor introduced by the same redesign (#962); 0–100 percentage. */
    val qualityRate: Double? = null,
)
