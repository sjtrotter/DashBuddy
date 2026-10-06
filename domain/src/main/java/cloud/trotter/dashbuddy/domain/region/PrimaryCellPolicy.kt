package cloud.trotter.dashbuddy.domain.region

data class CellObservation(val cell: RegionCell, val atMillis: Long)

object PrimaryCellPolicy {
    const val WINDOW_MILLIS = 28L * 24 * 60 * 60 * 1000
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /**
     * The install's primary cell: the most DISTINCT ACTIVE DAYS (UTC) within [WINDOW_MILLIS]
     * before [nowMillis]; ties → most recently seen; nothing in the window → null.
     * Both window endpoints are inclusive; future observations are excluded. Enrolment alone
     * never counts — only observations the caller derived from activity.
     * Exact recency ties use ascending wire form so input order never changes the result.
     */
    fun primaryCell(observations: List<CellObservation>, nowMillis: Long): RegionCell? {
        val start = if (nowMillis < Long.MIN_VALUE + WINDOW_MILLIS) Long.MIN_VALUE else nowMillis - WINDOW_MILLIS
        return observations.asSequence()
            .filter { it.atMillis in start..nowMillis }
            .groupBy { it.cell }
            .entries.sortedWith(
                compareByDescending<Map.Entry<RegionCell, List<CellObservation>>> { entry ->
                    entry.value.map { Math.floorDiv(it.atMillis, DAY_MILLIS) }.toSet().size
                }.thenByDescending { entry -> entry.value.maxOf { it.atMillis } }
                    .thenBy { it.key.wire },
            ).firstOrNull()?.key
    }
}
