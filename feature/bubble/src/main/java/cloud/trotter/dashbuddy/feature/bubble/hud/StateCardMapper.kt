package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.pay.displayLabel
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.UNKNOWN_STORE

object StateCardMapper {
    fun map(
        flow: Flow, mode: Mode?, active: FlowCardSnapshot?, stack: CardStack,
        region: PlatformRegion?, session: SessionRecord?,
    ): StateCardModel? {
        if (mode == Mode.Paused) return null
        return when (flow) {
            Flow.Idle, Flow.TaskUnassigned -> if (mode == Mode.Online) waiting(stack, region, session) else null
            Flow.TaskPickupNavigation, Flow.TaskPickupArrived -> (active as? FlowCardSnapshot.Pickup)
                ?.let { pickup(it, flow == Flow.TaskPickupArrived) }
            Flow.TaskDropoffNavigation, Flow.TaskDropoffArrived -> (active as? FlowCardSnapshot.Delivery)
                ?.let { delivery(it, flow == Flow.TaskDropoffArrived) }
            Flow.TaskActive -> when (active) {
                is FlowCardSnapshot.Pickup -> pickup(active, active.arrivedAt != null)
                is FlowCardSnapshot.Delivery -> delivery(active, active.arrivedAt != null)
                else -> null
            }
            Flow.PostTask -> (active as? FlowCardSnapshot.PostTask)?.let {
                StateCardModel.Receipt(it.storeName ?: UNKNOWN_STORE, it.phaseStartedAt,
                    it.parsedPay?.appPayComponents?.map { line -> ReceiptLine(line.displayLabel, line.amount) }.orEmpty(),
                    it.parsedPay?.totalTip, it.totalPay)
            }
            else -> null
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
