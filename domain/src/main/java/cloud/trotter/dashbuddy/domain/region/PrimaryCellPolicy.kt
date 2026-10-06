package cloud.trotter.dashbuddy.domain.region

data class CellObservation(val cell: RegionCell, val atMillis: Long)

object PrimaryCellPolicy {
    const val WINDOW_MILLIS = 28L * 24 * 60 * 60 * 1000
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /**
     * The install's primary cell: the most DISTINCT ACTIVE DAYS within [WINDOW_MILLIS]
     * before [nowMillis]; ties → most recently seen; nothing in the window → null.
     * Both window endpoints are inclusive; future observations are excluded. Enrolment alone
     * never counts — only observations the caller derived from activity.
     * Exact recency ties use ascending wire form so input order never changes the result.
     * Day buckets use the device's [zoneOffsetMillis], passed by the caller; UTC by default.
     * UTC midnight is 17:00 PDT, so without the offset a Pacific dinner shift spans two "days".
     */
    fun primaryCell(
        observations: List<CellObservation>,
        nowMillis: Long,
        zoneOffsetMillis: Long = 0L,
    ): RegionCell? {
        val start = if (nowMillis < Long.MIN_VALUE + WINDOW_MILLIS) Long.MIN_VALUE else nowMillis - WINDOW_MILLIS
        return observations.asSequence()
            .filter { it.atMillis in start..nowMillis }
            .groupBy { it.cell }
            .map { (cell, activity) ->
                Triple(
                    cell,
                    activity.map { Math.floorDiv(it.atMillis + zoneOffsetMillis, DAY_MILLIS) }.toSet().size,
                    activity.maxOf { it.atMillis },
                )
            }.sortedWith(
                compareByDescending<Triple<RegionCell, Int, Long>> { it.second }
                    .thenByDescending { it.third }
                    .thenBy { it.first.wire },
            ).firstOrNull()?.first
    }
}
