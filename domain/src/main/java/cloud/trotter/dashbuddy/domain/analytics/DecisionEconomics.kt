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
    /** Σ frozen `estNetPay` of the declined offers — the "value of saying no" (est., not realized). */
    val declinedEstNet: Double,
    /** Average frozen score of accepted offers, or `null` when none / all-null. */
    val avgScoreAccepted: Double?,
    /** Average frozen score of declined offers, or `null` when none / all-null. */
    val avgScoreDeclined: Double?,
    /** Average frozen est. $/hr of accepted offers, or `null` when none / all-null. */
    val avgEstPerHourAccepted: Double?,
    /** Average frozen est. $/hr of declined offers, or `null` when none / all-null. */
    val avgEstPerHourDeclined: Double?,
) {
    companion object {
        val EMPTY = DecisionEconomics(
            received = 0,
            accepted = 0,
            declined = 0,
            timedOut = 0,
            acceptanceRate = null,
            declinedEstNet = 0.0,
            avgScoreAccepted = null,
            avgScoreDeclined = null,
            avgEstPerHourAccepted = null,
            avgEstPerHourDeclined = null,
        )
    }
}
