package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.AnalyticsDao
import cloud.trotter.log.gig.core.database.analytics.RecapDao
import cloud.trotter.log.gig.core.database.analytics.ShiftDayRow
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindow
import cloud.trotter.log.gig.domain.analytics.recap.DayFacts
import cloud.trotter.log.gig.domain.analytics.recap.DayShift
import cloud.trotter.log.gig.domain.state.Platform
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * #1515: Recap's reads that keep a day's state. [days] is the per-day series with "pay seen" kept (never coalesced
 * to $0, unlike [AnalyticsRepository.dailyEarnings], which stays Home's); [shifts] is the shifts that started in a
 * window. Read-model only, Room-invalidation Flows, session-anchored: a shift lands wholly on its start day.
 */
@Singleton
class RecapRepository @Inject constructor(
    private val recapDao: RecapDao,
    private val analyticsDao: AnalyticsDao,
) {
    /** True once any shift has been recorded. */
    val anyShift: Flow<Boolean> get() = recapDao.anyShift()

    /** The shifts that started in [window], oldest first, open ones included. Empty for an unbounded window. */
    fun shifts(window: AnalyticsWindow, zone: ZoneId = ZoneId.systemDefault()): Flow<List<DayShift>> {
        if (window.isLifetime) return flowOf(emptyList())
        val bounds = PeriodBounds.of(window, zone)
        return recapDao.shiftDayRows(bounds.start, bounds.end).map { rows -> rows.map { it.toDomain() } }
    }

    /**
     * One [DayFacts] per local day of [window], in order: the shifts that started that day and the deliveries with
     * no shift that completed that day (they count as pay for their day). Empty for an unbounded window.
     */
    fun days(window: AnalyticsWindow, zone: ZoneId = ZoneId.systemDefault()): Flow<List<DayFacts>> {
        val first = window.startDate ?: return flowOf(emptyList())
        val endExclusive = window.endDateExclusive ?: return flowOf(emptyList())
        val bounds = PeriodBounds.of(window, zone)
        return combine(
            recapDao.shiftDayRows(bounds.start, bounds.end),
            analyticsDao.noSessionDailyRows(bounds.start, bounds.end),
        ) { shiftRows, noShiftRows ->
            val shiftsByDay = shiftRows.map { it.toDomain() }.groupBy { localDate(it.startedAt, zone) }
            val noShiftByDay = noShiftRows.groupBy { localDate(it.completedAt, zone) }
            generateSequence(first) { it.plusDays(1) }.takeWhile { it.isBefore(endExclusive) }.map { date ->
                val noShift = noShiftByDay[date].orEmpty()
                DayFacts(
                    date = date,
                    shifts = shiftsByDay[date].orEmpty(),
                    noShiftEarned = noShift.sumOf { it.gross },
                    noShiftProfit = noShift.sumOf { it.net },
                    noShiftDeliveries = noShift.size,
                )
            }.toList()
        }
    }

    private fun localDate(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun ShiftDayRow.toDomain() = DayShift(
        sessionId = sessionId,
        platform = Platform.fromWire(platform) ?: Platform.Unknown,
        startedAt = startedAt,
        endedAt = endedAt,
        earned = gross,
        profit = net,
        paySeen = paySeen,
    )
}
