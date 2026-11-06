package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.AnalyticsDao
import cloud.trotter.log.gig.domain.analytics.SessionMilesCorrectionFold
import cloud.trotter.log.gig.domain.analytics.SessionMilesRule
import cloud.trotter.log.gig.domain.model.event.payload.SessionMilesOperation
import timber.log.Timber

private const val TAG = "Projector"

/** The override one applied SESSION_MILES_CORRECTION wrote (#1535); null [miles] restores the tracked span. */
internal data class SessionMilesWrite(val miles: Double?)

/**
 * #1535: apply one driver SESSION_MILES_CORRECTION by session PK inside the batch transaction (the
 * `SessionReportApply.kt` twin). Returns the written override, or null when SKIPPED. Guards skip + WARN with ids and
 * enum names only, never throw; they are defence-in-depth behind `CorrectionFolds.foldSessionMilesCorrection`, which
 * already judged eligibility at the correction's log position. The odometer anchors are never touched.
 */
internal suspend fun AnalyticsDao.applySessionMilesCorrection(fold: SessionMilesCorrectionFold): SessionMilesWrite? {
    val session = sessionRecord(fold.sessionId) ?: run {
        Timber.tag(TAG).w("SESSION_MILES_CORRECTION: target session %s not found — skipped", fold.sessionId)
        return null
    }
    if (session.endedAt == null) {
        Timber.tag(TAG).w("SESSION_MILES_CORRECTION: target session %s still live — skipped", fold.sessionId)
        return null
    }
    val write = when (fold.operation) {
        SessionMilesOperation.SET -> {
            if (!SessionMilesRule.isValidSet(fold.miles)) {
                Timber.tag(TAG).w("SESSION_MILES_CORRECTION: SET value out of bounds for session %s — skipped", fold.sessionId)
                return null
            }
            SessionMilesWrite(fold.miles)
        }
        SessionMilesOperation.RESTORE_MACHINE -> SessionMilesWrite(null)
        else -> {
            Timber.tag(TAG).w("SESSION_MILES_CORRECTION: unknown operation for session %s — skipped", fold.sessionId)
            return null
        }
    }
    setSessionMilesOverride(fold.sessionId, write.miles)
    return write
}
