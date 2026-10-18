package cloud.trotter.dashbuddy.feature.bubble.hud

object TrackMapper {
    fun map(leg: TrackLeg, now: Long): TrackModel {
        val (anchor, end) = when (leg.kind) {
            LegKind.PICKUP_NAV -> TrackIcon.NAV_PIN to TrackIcon.STORE
            LegKind.PICKUP_ARRIVED -> TrackIcon.STORE to TrackIcon.DONE_PIN
            LegKind.DROPOFF_NAV -> TrackIcon.NAV_PIN to TrackIcon.HOUSE
            LegKind.DROPOFF_ARRIVED -> TrackIcon.HOUSE to TrackIcon.DONE_PIN
        }
        val deadline = leg.deadlineAt
            ?: return TrackModel(anchor, leg.anchorAt, end, null, null, false, 0f, null)
        return if (now >= deadline) {
            TrackModel(anchor, leg.anchorAt, null, now, deadline, true, 1f,
                fraction(deadline - leg.anchorAt, now - leg.anchorAt))
        } else {
            TrackModel(anchor, leg.anchorAt, end, deadline, null, false,
                fraction(now - leg.anchorAt, deadline - leg.anchorAt), null)
        }
    }

    private fun fraction(elapsed: Long, span: Long): Float =
        if (span <= 0) 0f else (elapsed.toDouble() / span).toFloat().coerceIn(0f, 1f)
}
