package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.model.event.payload.SessionEndSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionReportOperation

/**
 * #1135: WHERE a dash's effective reported total came from — the Kotlin statement of the
 * #1030/#1134 trust rule for readers (session detail, CSV). [SessionReportRule.effectiveReported]
 * stays the VALUE owner; SessionReportSql.REPORT_SOURCE_SQL is the matrix-tested SQL mirror.
 */
enum class ReportSource(val wire: String) {
    DASH_SUMMARY("DASH_SUMMARY"),
    IN_DASH_COUNTER("IN_DASH_COUNTER"),
    DRIVER_SET("DRIVER_SET"),
    NONE("NONE");

    companion object {
        fun of(reportedEarnings: Double?, endSource: String?, overrideMode: String?, override: Double?): ReportSource = when {
            SessionReportRule.effectiveReported(reportedEarnings, endSource, overrideMode, override) == null -> NONE
            overrideMode == SessionReportOperation.SET -> DRIVER_SET
            endSource == SessionEndSource.SUMMARY_SCREEN -> DASH_SUMMARY
            else -> IN_DASH_COUNTER
        }
    }
}
