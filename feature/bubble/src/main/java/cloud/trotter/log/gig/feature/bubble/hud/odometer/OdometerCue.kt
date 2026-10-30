package cloud.trotter.log.gig.feature.bubble.hud.odometer

import cloud.trotter.log.gig.domain.model.vehicle.LastOdometerReading

object OdometerCue {
    const val WINDOW_MS = 600_000L

    fun shows(
        enabled: Boolean, startedAt: Long?, endedAt: Long?, last: LastOdometerReading?,
        loaded: Boolean, now: Long,
    ): Boolean {
        if (!enabled || !loaded) return false
        return when {
            startedAt != null -> now >= startedAt && now - startedAt < WINDOW_MS &&
                (last == null || last.occurredAt < startedAt - WINDOW_MS)
            endedAt != null -> last == null || last.occurredAt < endedAt - WINDOW_MS
            else -> false
        }
    }
}
