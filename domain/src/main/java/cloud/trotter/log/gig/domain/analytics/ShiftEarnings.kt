package cloud.trotter.log.gig.domain.analytics

/**
 * #1443: the one owner of the bubble's headline, a shift's earnings. Cash tips and costs never enter it.
 *
 * `recordedPay` is the shift's recorded delivery pay ([SessionDetail.recordedPay]): counted once per delivery,
 * re-priced in place, cash-free, and including the `OFFER_PAY` rows of deliveries whose receipt was never read.
 * Null when no delivery of the shift has a priced row, so an unpriced shift shows the em dash, never `$0.00`
 * (#1041). `Session.accumulatedDeliveryPay` is never an input: a receipt re-entry can add it twice (#1460).
 */
object ShiftEarnings {
    /**
     * A live shift: the larger of the delivery app's running total and our recorded pay. The app's own total can
     * lag a delivery, and shows none on some deliveries; it overtakes our quote-priced pay once it catches up.
     */
    fun live(running: Double?, recordedPay: Double?): Double? = listOfNotNull(running, recordedPay).maxOrNull()

    /**
     * An ended shift: its effective report ([SessionReportRule]: a driver SET wins, a summary-screen total is
     * authoritative, a summary `$0.00` included), else its recorded pay. A driver CLEAR makes the effective report
     * null, so a cleared shift falls back to recorded pay. The SQL twin is analytics gross's
     * `COALESCE(effective, deliveredPay, 0)` in `AnalyticsDao.grossAndUnattributed`.
     */
    fun rested(effectiveReport: Double?, recordedPay: Double?): Double? = effectiveReport ?: recordedPay
}

/** An ended shift's inputs to the day's earnings. [effectiveReport] is already [SessionReportRule]'s. */
data class EndedShiftPay(
    val sessionId: String,
    val startedAt: Long,
    val effectiveReport: Double?,
    val recordedPay: Double?,
)

/** A live shift's inputs to the day's earnings, read from the platform region's session. */
data class LiveShiftPay(
    val sessionId: String,
    val startedAt: Long,
    val running: Double?,
    val recordedPay: Double?,
)

/**
 * #1443: the daily goal's figure, app earnings across every app for one local day: no cash, no costs. Each shift
 * counts once, by session id; a shift still live uses its live figure. Only shifts started inside
 * `[dayStart, dayEnd)` count, so yesterday's shift still live after midnight adds nothing. A null figure adds
 * nothing, and a day with nothing known is `0.0`.
 *
 * Accepted gap: between a shift leaving state and its `DASH_STOP` fold it is neither live nor ended here, so it
 * briefly adds nothing.
 */
object TodayEarnings {
    fun of(endedToday: List<EndedShiftPay>, live: List<LiveShiftPay>, dayStart: Long, dayEnd: Long): Double {
        fun inDay(startedAt: Long) = startedAt >= dayStart && startedAt < dayEnd
        val liveIds = live.mapTo(HashSet()) { it.sessionId }
        val liveFigures = live.filter { inDay(it.startedAt) }
            .associate { it.sessionId to ShiftEarnings.live(it.running, it.recordedPay) }
        val endedFigures = endedToday.filter { inDay(it.startedAt) && it.sessionId !in liveIds }
            .associate { it.sessionId to ShiftEarnings.rested(it.effectiveReport, it.recordedPay) }
        return (endedFigures + liveFigures).values.sumOf { it ?: 0.0 }
    }
}
