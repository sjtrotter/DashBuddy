package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.HudShiftDao
import cloud.trotter.log.gig.domain.analytics.EndedShiftPay
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** #1443: the bubble's shift reads, a small owner of its own beside [AnalyticsRepository]. */
@Singleton
class HudShiftRepository @Inject constructor(private val dao: HudShiftDao) {
    /**
     * [platform]'s newest shift row by start, open rows included; null before its first shift folds. Its effective
     * report is floored at its recorded pay (#1459), so a stale running total gives way at rest.
     */
    fun latestShift(platform: Platform): Flow<SessionRecord?> =
        dao.latestSession(platform.wire).map { it?.toDomain() }

    /**
     * Every ended shift started in `[start, end)`, on every platform, with its effective report
     * ([cloud.trotter.log.gig.domain.analytics.SessionReportRule], via [toDomain]) and recorded pay.
     */
    fun endedShiftsBetween(start: Long, end: Long): Flow<List<EndedShiftPay>> =
        dao.endedSessionsBetween(start, end).map { rows ->
            rows.map { row ->
                val session = row.toDomain()
                EndedShiftPay(session.sessionId, session.startedAt, session.reportedEarnings, row.recordedPay)
            }
        }
}
