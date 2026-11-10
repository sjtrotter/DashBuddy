package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.StoreSearchDao
import cloud.trotter.log.gig.domain.analytics.recap.DayShift
import cloud.trotter.log.gig.domain.analytics.search.ShiftMatches
import cloud.trotter.log.gig.domain.analytics.search.StoreQuery
import cloud.trotter.log.gig.domain.analytics.search.TimelineSearch
import cloud.trotter.log.gig.domain.analytics.timeline.ShiftTimeline
import cloud.trotter.log.gig.domain.state.Platform
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOf

/**
 * #1540: search past shifts by store. [matchingShifts] reads names and ids only, over every shift, and filters them
 * in Kotlin with [StoreQuery] before any page is taken, so a match in an old shift is found; [results] folds one page
 * of those shifts' timelines from three batched flows and keeps the matching rows ([TimelineSearch]). Read-model
 * only; store names are merchants, and the query is never logged.
 */
@Singleton
class StoreSearchRepository @Inject constructor(
    private val storeSearchDao: StoreSearchDao,
    private val recapRepository: RecapRepository,
) {
    /** The shifts with an offer or a delivery whose store matches [query], newest first. */
    fun matchingShifts(query: StoreQuery): Flow<List<DayShift>> =
        combine(storeSearchDao.offerNames(), storeSearchDao.deliveryNames(), recapRepository.allShifts()) { offers, drops, shifts ->
            val ids = HashSet<String>()
            offers.forEach { if (it.sessionId !in ids && query.matches(it.name)) ids += it.sessionId }
            drops.forEach { if (it.sessionId !in ids && query.matches(it.name)) ids += it.sessionId }
            shifts.filter { it.sessionId in ids }
        }.distinctUntilChangedBy { shifts -> shifts.map { ShiftHeader(it.sessionId, it.platform, it.startedAt, it.endedAt) } }

    /**
     * The matching rows of [shifts] (one page, in its order), each shift folded as running while it has no end. A shift
     * whose matches fold to no row (an offer of an outcome this build does not know) is left out.
     */
    fun results(
        shifts: List<DayShift>,
        query: StoreQuery,
        batchSize: Int = SEARCH_BATCH_SHIFTS,
    ): Flow<List<ShiftMatches>> {
        if (shifts.isEmpty()) return flowOf(emptyList())
        // Each batch binds at most [batchSize] ids, well under SQLite's 999 bind limit however many pages are loaded.
        val batches = shifts.chunked(batchSize).map { batch -> batchResults(batch, query) }
        return combine(batches) { parts -> parts.flatMap { it } }
    }

    private fun batchResults(shifts: List<DayShift>, query: StoreQuery): Flow<List<ShiftMatches>> {
        val ids = shifts.map { it.sessionId }
        return combine(
            storeSearchDao.offersForSessions(ids),
            storeSearchDao.pickupsForSessions(ids),
            storeSearchDao.deliveriesForSessions(ids),
        ) { offerRows, pickupRows, deliveryRows ->
            val offers = offerRows.groupBy({ it.sessionId }, { it.toTimeline() })
            val pickups = pickupRows.groupBy({ it.sessionId }, { it.toTimeline() })
            val deliveries = deliveryRows.groupBy({ it.sessionId }, { it.toTimeline() })
            shifts.mapNotNull { shift ->
                val o = offers[shift.sessionId].orEmpty()
                val d = deliveries[shift.sessionId].orEmpty()
                val items = ShiftTimeline.fold(o, pickups[shift.sessionId].orEmpty(), d, running = shift.endedAt == null)
                TimelineSearch.filter(items, o, d, query).takeIf { it.isNotEmpty() }?.let { ShiftMatches(shift, it) }
            }
        }
    }
}

/** What a result group shows of its shift; a change elsewhere in the shift (its pay) does not restart the reads. */
private data class ShiftHeader(val sessionId: String, val platform: Platform, val startedAt: Long, val endedAt: Long?)

/** Shifts per batched read: one page is 10, so a batch covers ten pages. */
internal const val SEARCH_BATCH_SHIFTS = 100
