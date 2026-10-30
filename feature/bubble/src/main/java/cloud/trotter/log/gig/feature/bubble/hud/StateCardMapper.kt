package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.model.cards.CardStack
import cloud.trotter.log.gig.domain.model.cards.FlowCardSnapshot
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.pay.displayLabel
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.UNKNOWN_STORE

object StateCardMapper {
    fun map(
        flow: Flow, mode: Mode?, active: FlowCardSnapshot?, stack: CardStack,
        region: PlatformRegion?, session: SessionRecord?, flowOwned: Boolean = true,
    ): StateCardModel? {
        if (mode == Mode.Paused) return null
        // The global flow describes the platform whose frame was last seen. When the display is pinned
        // to another platform (its live card suppressed), only that region's own state may speak.
        if (!flowOwned) return if (mode == Mode.Online && region?.activeTask == null) waiting(stack, region, session) else null
        // Independently collected flows can move before the card snapshot at a phase edge.
        return when (active) {
            is FlowCardSnapshot.Awaiting -> if (mode == Mode.Online) waiting(stack, region, session) else null
            is FlowCardSnapshot.Pickup -> pickup(active, active.arrivedAt != null || flow == Flow.TaskPickupArrived)
            is FlowCardSnapshot.Delivery -> delivery(active, active.arrivedAt != null || flow == Flow.TaskDropoffArrived)
            is FlowCardSnapshot.PostTask -> StateCardModel.Receipt(
                active.storeName ?: UNKNOWN_STORE, active.phaseStartedAt,
                active.parsedPay?.appPayComponents?.map { ReceiptLine(it.displayLabel, it.amount) }.orEmpty(),
                active.parsedPay?.totalTip, active.totalPay)
            is FlowCardSnapshot.Offer -> null
            null -> if ((flow == Flow.Idle || flow == Flow.TaskUnassigned) && mode == Mode.Online)
                waiting(stack, region, session) else null
        }
    }

    private fun pickup(card: FlowCardSnapshot.Pickup, arrived: Boolean): StateCardModel {
        val facts = JobFacts(card.storeName, card.deadlineMillis, card.netPay, card.distanceMiles)
        val at = card.arrivedAt?.takeIf { arrived } ?: return StateCardModel.PickupNav(facts, card.phaseStartedAt)
        val shopped = card.itemsShopped
        val remaining = card.itemsRemaining
        val total = shopped?.toLong()?.plus(remaining?.toLong() ?: 0)
        return if (shopped != null && remaining != null && shopped >= 0 && remaining >= 0 &&
            total != null && total in 1..Int.MAX_VALUE.toLong()) {
            StateCardModel.Shopping(facts, at, shopped, total.toInt())
        } else StateCardModel.PickupArrived(facts, at)
    }

    private fun delivery(card: FlowCardSnapshot.Delivery, arrived: Boolean): StateCardModel {
        val facts = JobFacts(card.storeName ?: UNKNOWN_STORE, card.deadlineMillis, card.netPay, card.distanceMiles)
        return card.arrivedAt?.takeIf { arrived }?.let { StateCardModel.DropoffArrived(facts, it) }
            ?: StateCardModel.DropoffNav(facts, card.phaseStartedAt)
    }

    private fun waiting(stack: CardStack, region: PlatformRegion?, session: SessionRecord?): StateCardModel.Waiting {
        val sessionId = region?.session?.sessionId
        val live = session?.takeIf { sessionId != null && it.sessionId == sessionId }
        val last = stack.completed.filterIsInstance<FlowCardSnapshot.Offer>()
            .filter { it.outcome != null && it.phaseEndedAt != null }.maxByOrNull { it.phaseEndedAt!! }
        val outcome = when (last?.outcome) {
            AppEventType.OFFER_ACCEPTED -> LastOfferOutcome.ACCEPTED
            AppEventType.OFFER_DECLINED -> LastOfferOutcome.DECLINED
            AppEventType.OFFER_TIMEOUT -> LastOfferOutcome.EXPIRED
            else -> null
        }
        return StateCardModel.Waiting(
            WaitingAnchor.resolve(region?.session?.startedAt,
                region?.let { it.recentTasks + listOfNotNull(it.activeTask) }.orEmpty(), stack),
            region?.session?.startedAt, live?.deliveries, live?.offersAccepted, live?.offersReceived,
            outcome?.let { LastOffer(last?.payAmount, it) })
    }
}
