package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.model.cards.CardStack
import cloud.trotter.log.gig.domain.model.cards.FlowCardSnapshot
import cloud.trotter.log.gig.domain.model.event.AppEventType

object RibbonMapper {
    fun map(stack: CardStack, dashStartedAt: Long): RibbonModel {
        val cards = (stack.completed + listOfNotNull(stack.active)).sortedBy { it.phaseStartedAt }
        fun endOf(card: FlowCardSnapshot): Long? = if (card === stack.active) null else card.phaseEndedAt
        // Jobs first: a pickup or dropoff is clipped only by the dash start and by another job,
        // never by a wait. An add-on offer decided mid-pickup must not cut the pickup short.
        val jobs = mutableListOf<RibbonSegment>()
        for (card in cards) {
            val kind = card.kind()
            if (kind == RibbonKind.WAIT) continue
            // An open job (the active card) runs to now and absorbs any job card that starts after it.
            // The ribbon is one dash's picture; only the dev-only MERGED two-dash view can put another
            // dash's completed job after the live card's start, and that job is not drawn here (accepted
            // in PR #1345 round 3 rather than splitting the live job around a foreign one).
            if (jobs.isNotEmpty() && jobs.last().endAt == null) continue
            val start = maxOf(dashStartedAt, card.phaseStartedAt, jobs.lastOrNull()?.endAt ?: dashStartedAt)
            val end = endOf(card)
            if (end != null && end <= start) continue
            val previous = jobs.lastOrNull()
            if (previous != null && previous.kind == kind && previous.endAt == start) {
                jobs[jobs.lastIndex] = previous.copy(endAt = end)
            } else jobs += RibbonSegment(kind, start, end)
        }
        // Waits fill whatever the jobs leave: each wait card is cut into the pieces outside every job.
        val waits = mutableListOf<RibbonSegment>()
        for (card in cards) {
            if (card.kind() != RibbonKind.WAIT) continue
            var cursor = maxOf(dashStartedAt, card.phaseStartedAt)
            val end = endOf(card)
            if (end != null && end <= cursor) continue
            for (job in jobs) {
                val jobEnd = job.endAt ?: Long.MAX_VALUE
                if (jobEnd <= cursor) continue
                if (end != null && job.startAt >= end) break
                if (job.startAt > cursor) waits += RibbonSegment(RibbonKind.WAIT, cursor, job.startAt)
                cursor = maxOf(cursor, jobEnd)
                if (cursor == Long.MAX_VALUE) break
            }
            if (cursor != Long.MAX_VALUE && (end == null || cursor < end)) waits += RibbonSegment(RibbonKind.WAIT, cursor, end)
        }
        val merged = mutableListOf<RibbonSegment>()
        for (segment in (jobs + waits).sortedWith(compareBy({ it.startAt }, { it.endAt ?: Long.MAX_VALUE }))) {
            val previous = merged.lastOrNull()
            if (previous == null) { merged += segment; continue }
            if (previous.endAt == null) continue // an open segment runs to now and absorbs the rest
            if (previous.kind == segment.kind && previous.endAt >= segment.startAt) {
                // Same kind, touching or overlapping: one segment, open if either side is open.
                val end = if (segment.endAt == null) null else maxOf(previous.endAt, segment.endAt)
                merged[merged.lastIndex] = previous.copy(endAt = end)
                continue
            }
            // Different kinds never overlap after the cut; clip defensively rather than drop.
            val start = maxOf(segment.startAt, previous.endAt)
            if (segment.endAt != null && segment.endAt <= start) continue
            merged += segment.copy(startAt = start)
        }
        val marks = cards.filterIsInstance<FlowCardSnapshot.Offer>()
            .filter { it.outcome != null && it.phaseStartedAt >= dashStartedAt }
            .map { OfferMark(it.phaseStartedAt, it.outcome == AppEventType.OFFER_ACCEPTED) }
        return RibbonModel(dashStartedAt, merged, marks, stack.active?.kind())
    }

    private fun FlowCardSnapshot.kind(): RibbonKind = when (this) {
        is FlowCardSnapshot.Pickup -> RibbonKind.PICKUP
        is FlowCardSnapshot.Delivery -> RibbonKind.DROPOFF
        is FlowCardSnapshot.Awaiting, is FlowCardSnapshot.Offer, is FlowCardSnapshot.PostTask -> RibbonKind.WAIT
    }
}
