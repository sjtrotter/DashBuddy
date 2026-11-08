package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck
import cloud.trotter.log.gig.domain.analytics.checks.ShiftChecks
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * One shift's checks (#1517): its detail and its folded timeline (folded as running while the shift has no end),
 * judged by [ShiftChecks]. Read-model only; re-emits on every projector commit. Empty for an unknown shift.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ShiftChecksRepository @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val shiftTimelineRepository: ShiftTimelineRepository,
) {
    fun checks(sessionId: String): Flow<List<ShiftCheck>> {
        val detail = analyticsRepository.sessionDetail(sessionId)
        val timeline = detail.map { it?.session?.endedAt == null }
            .distinctUntilChanged()
            .flatMapLatest { running -> shiftTimelineRepository.timeline(sessionId, running) }
        return combine(detail, timeline) { d, t -> d?.let { ShiftChecks.of(it, t) }.orEmpty() }
    }

    /** #1515: each shift's [checks] in [sessionIds], keyed by id (Recap's Shifts to check). */
    fun checks(sessionIds: List<String>): Flow<Map<String, List<ShiftCheck>>> =
        if (sessionIds.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(sessionIds.map { id -> checks(id).map { id to it } }) { pairs -> pairs.toMap() }
        }
}
