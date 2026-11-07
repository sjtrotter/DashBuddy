package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionReportOperation

/**
 * #1135: WHERE a dash's effective reported total came from — the Kotlin statement of the
 * #1030/#1134 trust rule for readers (session detail, CSV). [SessionReportRule.effectiveReported]
 * stays the VALUE owner; SessionReportSql.REPORT_SOURCE_SQL is the matrix-tested SQL mirror.
 * #1459: [RECORDED_PAY] is a non-summary running total floored at the shift's higher recorded pay; a tie
 * ([SessionReportRule.recordedPayAbove]) stays [IN_DASH_COUNTER].
 */
enum class ReportSource(val wire: String) {
    DASH_SUMMARY("DASH_SUMMARY"),
    IN_DASH_COUNTER("IN_DASH_COUNTER"),
    RECORDED_PAY("RECORDED_PAY"),
    DRIVER_SET("DRIVER_SET"),
    NONE("NONE");

    /**
     * A total that did not come from the shift's own delivery pay: captured from the app's summary or set by the
     * dasher. Only such a total can be checked against delivery pay; a running total floored at recorded pay, or
     * recorded pay standing in for a missing report, would always match. The shift page's money check reads this.
     */
    val independent: Boolean get() = this == DASH_SUMMARY || this == DRIVER_SET

    companion object {
        fun of(
            reportedEarnings: Double?,
            endSource: String?,
            overrideMode: String?,
            override: Double?,
            deliveredPay: Double?,
        ): ReportSource = when {
            SessionReportRule.effectiveReported(reportedEarnings, endSource, overrideMode, override, deliveredPay) == null -> NONE
            overrideMode == SessionReportOperation.SET -> DRIVER_SET
            endSource == SessionEndSource.SUMMARY_SCREEN -> DASH_SUMMARY
            reportedEarnings != null && SessionReportRule.recordedPayAbove(reportedEarnings, deliveredPay) -> RECORDED_PAY
            else -> IN_DASH_COUNTER
        }
    }
}
