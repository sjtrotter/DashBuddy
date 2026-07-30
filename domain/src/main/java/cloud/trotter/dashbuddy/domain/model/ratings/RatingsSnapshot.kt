package cloud.trotter.dashbuddy.domain.model.ratings

import kotlinx.serialization.Serializable

/**
 * A point-in-time snapshot of the dasher's performance metrics, captured when the Ratings
 * screen is observed. Carried in app state so the bubble HUD can display it
 * on the idle card without re-observing the ratings screen.
 *
 * ## Opportunistic capture — DISPLAY / RECORDS ONLY (dev ruling 2026-07-30)
 *
 * **This data updates only when the dasher happens to view the Ratings surface.**
 * It may be null, or arbitrarily stale, forever — a dasher who never opens that
 * screen produces no snapshot at all, and one who opened it in March carries
 * March's numbers indefinitely. There is no polling, no refresh, and no way to
 * ask for it: the platform decides when we see it.
 *
 * **Therefore no decision path may consume these fields.** Not the offer
 * evaluator, not the state machine / steppers, not the effect map or any effect
 * handler, not the analytics folds or any frozen economics column. The only
 * legal consumers are the read-side surfaces that *show* the numbers back to the
 * dasher (the Ratings screen, the bubble idle card) and, in future, records that
 * are explicitly timestamped as a captured observation.
 *
 * The temptation is real and specific: protect-stats looks like it wants an
 * acceptance rate. It does not — protect-stats is a **behavior, not a
 * calculation**, and needs no rating input. Anything that branched on a value
 * this unreliable would silently behave differently for a dasher who browses
 * their ratings than for one who doesn't, which is a correctness bug wearing a
 * feature's clothes.
 *
 * `RatingsSnapshotIsDisplayOnlyTest` (`:core:state`) is the cheap guard: it
 * source-scans `:domain` evaluation and the `:core:state` steppers/effect map and
 * fails if anything but the one display-stamping site names this type.
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
