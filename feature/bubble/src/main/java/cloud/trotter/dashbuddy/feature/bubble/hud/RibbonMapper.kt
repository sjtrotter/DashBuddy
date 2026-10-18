package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.event.AppEventType

object RibbonMapper {
    fun map(stack: CardStack, dashStartedAt: Long): RibbonModel {
        val cards = stack.completed.sortedBy { it.phaseStartedAt } + listOfNotNull(stack.active)
        val segments = mutableListOf<RibbonSegment>()
        for (card in cards) {
            val start = maxOf(dashStartedAt, card.phaseStartedAt, segments.lastOrNull()?.endAt ?: dashStartedAt)
            val end = if (card === stack.active) null else (card.phaseEndedAt ?: continue)
            if (end != null && end <= start) continue
            val segment = RibbonSegment(card.kind(), start, end)
            val previous = segments.lastOrNull()
            if (previous != null && previous.kind == segment.kind && previous.endAt == start) {
                segments[segments.lastIndex] = previous.copy(endAt = end)
            } else segments += segment
        }
        val marks = cards.filterIsInstance<FlowCardSnapshot.Offer>()
            .filter { it.outcome != null && it.phaseStartedAt >= dashStartedAt }
            .sortedBy { it.phaseStartedAt }
            .map { OfferMark(it.phaseStartedAt, it.outcome == AppEventType.OFFER_ACCEPTED) }
        return RibbonModel(dashStartedAt, segments, marks, stack.active?.kind())
    }

    private fun FlowCardSnapshot.kind(): RibbonKind = when (this) {
        is FlowCardSnapshot.Pickup -> RibbonKind.PICKUP
        is FlowCardSnapshot.Delivery -> RibbonKind.DROPOFF
        is FlowCardSnapshot.Awaiting, is FlowCardSnapshot.Offer, is FlowCardSnapshot.PostTask -> RibbonKind.WAIT
    }
}
