package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.domain.analytics.SessionReportCorrectionFold
import cloud.trotter.dashbuddy.domain.analytics.SessionReportRule
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionReportOperation
import timber.log.Timber

private const val TAG = "Projector"

/** The three override columns one applied SESSION_REPORT_CORRECTION wrote (#1134) — what the projector copies onto an in-memory context. */
internal data class SessionReportWrite(val mode: String?, val value: Double?, val correctedAt: Long?)

/**
 * #1134 — apply one driver SESSION_REPORT_CORRECTION by session PK inside the batch transaction (its own file:
 * `AnalyticsProjector.kt` is past the P3 ceiling, the `LegFolds`/`CorrectionFolds` split precedent). Returns the
 * written triple, or null when SKIPPED. Guards skip + WARN with ids/enum names only, never throw; they are
 * defence-in-depth behind `CorrectionFolds.foldSessionReportCorrection`, which already judged eligibility at the
 * correction's log position. The machine `reportedEarnings` column is never touched.
 */
internal suspend fun AnalyticsDao.applySessionReportCorrection(fold: SessionReportCorrectionFold, occurredAt: Long): SessionReportWrite? {
    val session = sessionRecord(fold.sessionId) ?: run {
        Timber.tag(TAG).w("SESSION_REPORT_CORRECTION: target session %s not found — skipped", fold.sessionId)
        return null
    }
    if (session.endedAt == null) {
        Timber.tag(TAG).w("SESSION_REPORT_CORRECTION: target session %s still live — skipped", fold.sessionId)
        return null
    }
    val write = when (fold.operation) {
        SessionReportOperation.SET -> {
            if (!SessionReportRule.isValidSet(fold.value)) {
                Timber.tag(TAG).w("SESSION_REPORT_CORRECTION: SET value out of bounds for session %s — skipped", fold.sessionId)
                return null
            }
            SessionReportWrite(SessionReportOperation.SET, fold.value, occurredAt)
        }
        SessionReportOperation.CLEAR -> SessionReportWrite(SessionReportOperation.CLEAR, null, occurredAt)
        SessionReportOperation.RESTORE_MACHINE -> SessionReportWrite(null, null, null)
        else -> {
            Timber.tag(TAG).w("SESSION_REPORT_CORRECTION: unknown operation for session %s — skipped", fold.sessionId)
            return null
        }
    }
    setSessionReportOverride(fold.sessionId, write.mode, write.value, write.correctedAt)
    return write
}
