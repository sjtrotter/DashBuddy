package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionReportOperation

/**
 * #1030 + #1134: the effective platform-reported total of a dash. A driver SET wins; a driver CLEAR
 * is no report; otherwise the machine value under the #1030 trust rule — a stored 0.0 is a real
 * parsed $0 only when the dash ended on the summary screen. ONE owner: the Room DAO mirrors it as
 * SQL (SessionReportSql.EFFECTIVE_REPORTED_SQL), with a matrix test proving agreement.
 *
 * #1459: on a non-summary end a positive running total is floored at the shift's recorded pay
 * ([recordedPayOf] over all its deliveries) when that pay is above it by more than half a cent
 * ([recordedPayAbove]), at read time, so past shifts change too. A summary total stays authoritative even
 * when lower; an absent or non-positive running total stays no report.
 */
object SessionReportRule {
    const val MAX_REPORTED = 10_000.0

    /** The ONE validity rule for a SET value (dialog, repository and projector all call this): finite, 0 ≤ v ≤ [MAX_REPORTED]. */
    fun isValidSet(value: Double?): Boolean = value != null && value.isFinite() && value >= 0.0 && value <= MAX_REPORTED

    fun effectiveReported(
        reportedEarnings: Double?,
        endSource: String?,
        overrideMode: String?,
        override: Double?,
        deliveredPay: Double?,
    ): Double? = when (overrideMode) {
        SessionReportOperation.SET -> override
        SessionReportOperation.CLEAR -> null
        else -> if (endSource == SessionEndSource.SUMMARY_SCREEN) {
            reportedEarnings
        } else {
            reportedEarnings?.takeIf { it > 0.0 }?.let { running ->
                deliveredPay?.takeIf { recordedPayAbove(running, it) } ?: running
            }
        }
    }

    /**
     * #1459: recorded pay is above the running total by more than [ANALYTICS_MONEY_EPSILON], so a sum of whole
     * cents that ties the total in floating point (0.10 + 0.20 against 0.30) stays a tie. ONE owner for the value
     * ([effectiveReported]) and the label ([ReportSource.of]); SessionReportSql.RECORDED_PAY_ABOVE_SQL mirrors it.
     */
    fun recordedPayAbove(running: Double, deliveredPay: Double?): Boolean =
        deliveredPay != null && deliveredPay > running + ANALYTICS_MONEY_EPSILON

    /**
     * #1459: a shift's recorded pay, Σ non-null `realizedPay` over all its deliveries (assigned rows included),
     * cash-free; null when no delivery is priced. The SQL twin is `SUM(realizedPay)` in
     * SessionReportSql.SESSION_DELIVERY_JOIN_SQL.
     */
    fun recordedPayOf(pays: Iterable<Double?>): Double? = pays.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
}
