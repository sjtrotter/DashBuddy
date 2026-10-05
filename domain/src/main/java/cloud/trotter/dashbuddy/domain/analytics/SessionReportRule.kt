package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.model.event.payload.SessionEndSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionReportOperation

/**
 * #1030 + #1134: the effective platform-reported total of a dash. A driver SET wins; a driver CLEAR
 * is no report; otherwise the machine value under the #1030 trust rule — a stored 0.0 is a real
 * parsed $0 only when the dash ended on the summary screen. ONE owner: the Room DAO mirrors it as
 * SQL (SessionReportSql.EFFECTIVE_REPORTED_SQL), with a matrix test proving agreement.
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
    ): Double? = when (overrideMode) {
        SessionReportOperation.SET -> override
        SessionReportOperation.CLEAR -> null
        else -> reportedEarnings?.takeIf { it > 0.0 || endSource == SessionEndSource.SUMMARY_SCREEN }
    }
}
