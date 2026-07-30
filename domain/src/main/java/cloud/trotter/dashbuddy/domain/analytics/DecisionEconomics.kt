package cloud.trotter.dashbuddy.domain.analytics

/**
 * Offer-decision economics for an [AnalyticsPeriod] (#315 H3, Decisions tab) — the read model behind
 * the offer funnel, the value-of-saying-no callout, and the score-vs-outcome comparison. Aggregated
 * over the period's **closing** offer records (`offer_records`), grouped by outcome.
 *
 * **Every economic figure here is a FROZEN decision-time estimate, never realized net.** The offer's
 * `estNetPay` / `estDollarsPerHour` are what the evaluator said at the moment of decision — an
 * economy edit does not re-cost a past offer (Principle 5). The UI labels these "est." so the dasher
 * reads them as decision-time projections, not what was actually earned.
 *
 * Nullable where a denominator/group is empty (no fabricated zeros): [acceptanceRate] is null with no
 * offers; the average score / est-$/hr fields are null when their outcome group is empty or every
 * member carried a null estimate.
 */
data class DecisionEconomics(
    /** Closing offers seen = [accepted] + [declined] + [timedOut]. */
    val received: Int,
    val accepted: Int,
    val declined: Int,
    val timedOut: Int,
    /** [accepted] ÷ [received], or `null` when no offers closed in the period. */
    val acceptanceRate: Double?,
    /**
     * Σ frozen `estNetPay` of the declined offers — the "value of saying no" (est., not realized).
     *
     * **A declined offer with NO frozen estimate contributes nothing** (SQL `SUM` skips nulls), which
     * is the #936 population: an offer the evaluator refused to score for want of a distance carries
     * null economics rather than a fabricated zero, so it can neither drag nor pad this sum. That
     * makes the sum honest but the *headline* incomplete, so [declinedWithEstimate] carries the
     * population alongside it — the surface states "across N of M declines with estimates" rather
     * than implying every decline is priced (§9 — thin data is stated, never smoothed over).
     */
    val declinedEstNet: Double,
    /**
     * How many of the [declined] offers carried a frozen `estNetPay` at all — the numerator of the
     * population statement above. Zero means the whole [declinedEstNet] is `0.0` for want of any
     * estimate, NOT because the declines were worthless: see [hasDeclinedEstimates].
     */
    val declinedWithEstimate: Int,
    /** Average frozen score of accepted offers, or `null` when none / all-null. */
    val avgScoreAccepted: Double?,
    /** Average frozen score of declined offers, or `null` when none / all-null. */
    val avgScoreDeclined: Double?,
    /** Average frozen est. $/hr of accepted offers, or `null` when none / all-null. */
    val avgEstPerHourAccepted: Double?,
    /** Average frozen est. $/hr of declined offers, or `null` when none / all-null. */
    val avgEstPerHourDeclined: Double?,
) {

    /**
     * True when at least one decline carried a frozen estimate — the only case in which
     * [declinedEstNet] is a statement about money rather than about missing data. With zero
     * coverage the sum is structurally `0.0`, and rendering "$0.00" would read as "you skipped
     * nothing of value", which is a lie about the data dressed as a fact about the pay.
     */
    val hasDeclinedEstimates: Boolean get() = declinedWithEstimate > 0

    /**
     * True when EVERY decline carried an estimate — the only case in which [declinedEstNet] is the
     * whole of what was skipped. Otherwise the figure is a **floor** and the copy must say so.
     */
    val declinedEstimatesComplete: Boolean
        get() = declined > 0 && declinedWithEstimate >= declined

    companion object {
        val EMPTY = DecisionEconomics(
            received = 0,
            accepted = 0,
            declined = 0,
            timedOut = 0,
            acceptanceRate = null,
            declinedEstNet = 0.0,
            declinedWithEstimate = 0,
            avgScoreAccepted = null,
            avgScoreDeclined = null,
            avgEstPerHourAccepted = null,
            avgEstPerHourDeclined = null,
        )
    }
}
