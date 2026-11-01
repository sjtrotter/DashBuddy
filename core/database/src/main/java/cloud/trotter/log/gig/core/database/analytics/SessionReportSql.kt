package cloud.trotter.log.gig.core.database.analytics

import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionReportOperation

/**
 * #1134 — the SQL mirror of `SessionReportRule.effectiveReported` (the Kotlin owner), for a `session_records`
 * alias `s` joined to [SESSION_DELIVERY_JOIN_SQL]'s alias `d`: a driver SET wins, a CLEAR is no report, a
 * summary-screen machine value is trusted verbatim, any other machine value only when positive (the #1030 rule;
 * `WHEN … > 0`, never `NULLIF(…, 0)`, so a negative non-summary value is no report here too — Astra r1 P2), and
 * that positive running total gives way to the shift's recorded pay when the pay is above it (#1459,
 * [RECORDED_PAY_ABOVE_SQL]). Operation and end-source wire strings are interpolated from their `:domain`
 * constants (fable review F5), never retyped. A source × machine × override × delivered matrix test in
 * `:core:data` proves SQL ≡ Kotlin on gross, unattributed, per-platform and per-day reads.
 */
object SessionReportSql {
    /**
     * #1459: the ONE per-session delivery join every `s`-anchored read uses, so the recorded-pay population (all
     * rows with the session's id, assigned rows included) has one definition. Contract: alias `d`, one row per
     * session (no fan-out), columns `deliveredPay` (`SUM(realizedPay)`, cash-free, NULL when nothing is priced),
     * `cashTip`, `net` (`SUM(netProfit)`) and `deliveries` (`COUNT(*)`). A query that uses [EFFECTIVE_REPORTED_SQL]
     * or [REPORT_SOURCE_SQL] must include it; Room's compile check fails one that lacks `d`.
     */
    const val SESSION_DELIVERY_JOIN_SQL =
        "LEFT JOIN (SELECT sessionId, SUM(realizedPay) AS deliveredPay, SUM(cashTip) AS cashTip, " +
            "SUM(netProfit) AS net, COUNT(*) AS deliveries FROM delivery_records GROUP BY sessionId) d " +
            "ON d.sessionId = s.sessionId"

    /**
     * #1459: SQL mirror of `SessionReportRule.recordedPayAbove`. The `0.005` is `ANALYTICS_MONEY_EPSILON`, written
     * out because a Double cannot be interpolated into a Room annotation string; a test pins the two together.
     * A NULL `deliveredPay` fails the comparison.
     */
    const val RECORDED_PAY_ABOVE_SQL = "d.deliveredPay > s.reportedEarnings + 0.005"

    const val EFFECTIVE_REPORTED_SQL =
        "CASE WHEN s.reportOverrideMode = '${SessionReportOperation.SET}' THEN s.reportOverride " +
            "WHEN s.reportOverrideMode = '${SessionReportOperation.CLEAR}' THEN NULL " +
            "WHEN s.endSource = '${SessionEndSource.SUMMARY_SCREEN}' THEN s.reportedEarnings " +
            "WHEN s.reportedEarnings > 0 THEN " +
            "CASE WHEN $RECORDED_PAY_ABOVE_SQL THEN d.deliveredPay ELSE s.reportedEarnings END " +
            "ELSE NULL END"

    /** SQL mirror of ReportSource.of; literals are pinned to ReportSource.wire in tests.
     * Enum properties cannot be interpolated into Room's compile-time annotation strings. */
    const val REPORT_SOURCE_SQL =
        "CASE WHEN ($EFFECTIVE_REPORTED_SQL) IS NULL THEN 'NONE' " +
            "WHEN s.reportOverrideMode = '${SessionReportOperation.SET}' THEN 'DRIVER_SET' " +
            "WHEN s.endSource = '${SessionEndSource.SUMMARY_SCREEN}' THEN 'DASH_SUMMARY' " +
            "WHEN $RECORDED_PAY_ABOVE_SQL THEN 'RECORDED_PAY' " +
            "ELSE 'IN_DASH_COUNTER' END"
}
