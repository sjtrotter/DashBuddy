package cloud.trotter.log.gig.domain.model.vehicle

enum class DashVehicleKind {
    START, SWAP;

    companion object {
        fun fromName(name: String): DashVehicleKind = entries.firstOrNull { it.name == name } ?: SWAP
    }
}

data class DashVehicleStamp(
    val sessionId: String, val vehicleId: String?, val gpsTotalMiles: Double?,
    val occurredAt: Long, val sequenceId: Long, val kind: DashVehicleKind,
)

/** Latest vehicle wins; the first stamp retains the dash's time and GPS origin. */
data class DashVehicle(val sessionId: String, val vehicleId: String?, val startedAt: Long, val startGps: Double?)
data class LiveDash(val sessionId: String, val startedAt: Long)
data class VehicleUsage(val dashes: Int, val miles: Double?)

sealed interface DashVehicleAction {
    data class Stamp(val sessionId: String, val vehicleId: String?, val kind: DashVehicleKind) : DashVehicleAction
    data class ThisDash(val sessionId: String, val vehicleId: String) : DashVehicleAction
    data class NextDash(val sessionId: String, val vehicleId: String) : DashVehicleAction
}

object DashVehicles {
    /** Count latest-vehicle dashes; only spans with both GPS boundaries contribute miles. */
    fun usageFor(dashes: List<DashVehicle>, vehicleId: String, gpsNow: Double?): VehicleUsage {
        var count = 0
        var miles: Double? = null
        dashes.forEachIndexed { index, dash ->
            if (dash.vehicleId != vehicleId) return@forEachIndexed
            count++
            val start = dash.startGps ?: return@forEachIndexed
            val end = if (index == dashes.lastIndex) gpsNow else dashes[index + 1].startGps
            if (end != null && start.isFinite() && end.isFinite() && end >= start) {
                miles = (miles ?: 0.0) + (end - start)
            }
        }
        return VehicleUsage(count, miles)
    }

    /** The vehicle cannot change while any live dash is past its grace window. */
    fun swapLocked(live: List<LiveDash>, now: Long, windowMs: Long): Boolean =
        live.any { now - it.startedAt > windowMs }

    /** Durable sequence, not wall-clock order, decides both the first and latest stamp. */
    fun perDash(stamps: List<DashVehicleStamp>): List<DashVehicle> =
        stamps.sortedBy { it.sequenceId }.groupBy { it.sessionId }.values.map { history ->
            val first = history.first()
            DashVehicle(first.sessionId, history.last().vehicleId, first.occurredAt, first.gpsTotalMiles)
        }

    /**
     * Each dash owns [its start GPS, the next start GPS); the last is open. Returns null
     * before coverage, for an unknown vehicle or boundary affecting the window, or non-increasing starts
     * (a GPS reset). Never attributes earlier history using today's active vehicle.
     */
    fun milesFor(dashes: List<DashVehicle>, vehicleId: String, from: Double, to: Double): Double? {
        if (!from.isFinite() || !to.isFinite() || to < from) return null
        val first = dashes.firstOrNull()?.startGps ?: return null
        if (from < first) return null
        val known = dashes.mapNotNull { it.startGps }
        if (known.any { !it.isFinite() } || known.zipWithNext().any { (a, b) -> b <= a }) return null
        // Scan backwards so a missing boundary is bounded by the nearest known starts.
        // Later fully known spans remain usable, without assigning an unknown interval.
        var nextStart = Double.POSITIVE_INFINITY
        var missingBoundary = false
        var miles = 0.0
        for (dash in dashes.asReversed()) {
            val start = dash.startGps
            if (start == null) {
                missingBoundary = true
                continue
            }
            if (missingBoundary && to > start && from < nextStart) return null
            if (dash.vehicleId == null && to > start && from < nextStart) return null
            if (dash.vehicleId == vehicleId) {
                miles += (minOf(to, nextStart) - maxOf(from, start)).coerceAtLeast(0.0)
            }
            nextStart = start
            missingBoundary = false
        }
        return miles
    }

    /** Start once; allow latest-wins swaps while the dash itself is inside the grace window. */
    fun decide(
        live: List<LiveDash>, activeVehicleId: String?, stamps: List<DashVehicleStamp>,
        now: Long, windowMs: Long,
    ): List<DashVehicleAction> {
        val vehicle = activeVehicleId
        val bySession = perDash(stamps).associateBy { it.sessionId }
        return live.flatMap { dash ->
            val stamped = bySession[dash.sessionId]
            when {
                stamped == null -> listOf(DashVehicleAction.Stamp(dash.sessionId,
                    vehicle.takeIf { now - dash.startedAt <= windowMs }, DashVehicleKind.START))
                vehicle == null || stamped.vehicleId == vehicle -> emptyList()
                // The window runs from the dash's own start, never from a stamp: a dash first seen late
                // carries a late unknown START, and a restart must not reopen the swap window from it (review r2).
                now - dash.startedAt <= windowMs -> listOf(
                    DashVehicleAction.Stamp(dash.sessionId, vehicle, DashVehicleKind.SWAP),
                    DashVehicleAction.ThisDash(dash.sessionId, vehicle),
                )
                else -> listOf(DashVehicleAction.NextDash(dash.sessionId, vehicle))
            }
        }
    }
}
