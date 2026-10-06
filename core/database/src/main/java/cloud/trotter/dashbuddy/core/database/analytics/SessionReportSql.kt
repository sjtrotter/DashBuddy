package cloud.trotter.dashbuddy.core.database.analytics

import cloud.trotter.dashbuddy.domain.model.event.payload.SessionEndSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionReportOperation

/**
 * #1134 — the SQL mirror of `SessionReportRule.effectiveReported` (the Kotlin owner), for a `session_records`
 * alias `s`: a driver SET wins, a CLEAR is no report, a summary-screen machine value is trusted verbatim, any
 * other machine value only when positive (the #1030 rule; `WHEN … > 0`, never `NULLIF(…, 0)`, so a negative
 * non-summary value is no report here too — Astra r1 P2). Operation and end-source wire strings are interpolated
 * from their `:domain` constants (fable review F5), never retyped. A source × machine × override matrix test in
 * `:core:data` proves SQL ≡ Kotlin on gross, unattributed, per-platform and per-day reads.
 */
object SessionReportSql {
    const val EFFECTIVE_REPORTED_SQL =
        "CASE WHEN s.reportOverrideMode = '${SessionReportOperation.SET}' THEN s.reportOverride " +
            "WHEN s.reportOverrideMode = '${SessionReportOperation.CLEAR}' THEN NULL " +
            "WHEN s.endSource = '${SessionEndSource.SUMMARY_SCREEN}' THEN s.reportedEarnings " +
            "WHEN s.reportedEarnings > 0 THEN s.reportedEarnings ELSE NULL END"

    /** SQL mirror of ReportSource.of; literals are pinned to ReportSource.wire in tests.
     * Enum properties cannot be interpolated into Room's compile-time annotation strings. */
    const val REPORT_SOURCE_SQL =
        "CASE WHEN ($EFFECTIVE_REPORTED_SQL) IS NULL THEN 'NONE' " +
            "WHEN s.reportOverrideMode = '${SessionReportOperation.SET}' THEN 'DRIVER_SET' " +
            "WHEN s.endSource = '${SessionEndSource.SUMMARY_SCREEN}' THEN 'DASH_SUMMARY' " +
            "ELSE 'IN_DASH_COUNTER' END"
}
