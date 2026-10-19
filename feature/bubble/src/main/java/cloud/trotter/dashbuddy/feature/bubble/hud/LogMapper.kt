package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.chat.ChatMessage
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.TaskUnassignedPayload
import cloud.trotter.dashbuddy.domain.state.UNKNOWN_STORE

/** Pure presentation fold. Projection values own money and voids; events own attribution and times. */
object LogMapper {
    fun map(
        cards: CardStack, data: LogData, chat: List<ChatMessage>, live: StateCardModel?,
        pendingTips: List<PendingCashTip> = emptyList(), undo: UndoWindow? = null,
    ): List<LogItem> {
        val tips = CashTipFold.fold(data.events, data.deliveries)
        val groups = JobGrouper.group(cards, data.events)
        val records = data.deliveries.associateBy { it.taskId }
        val unassigned = data.events.filter { it.type == AppEventType.TASK_UNASSIGNED }
            .mapNotNull { it.payload as? TaskUnassignedPayload }
            .mapTo(HashSet()) { it.jobId to it.taskId }
        val completed = data.events.filter { it.type == AppEventType.DELIVERY_COMPLETED }
            .mapNotNull { event -> (event.payload as? DeliveryPayload)?.let { it to event.occurredAt } }
            .groupBy { (delivery, _) -> delivery.jobId }
        val joined = groups.flatMap { it.offers }.mapTo(HashSet()) { it.id }
        val rows = mutableListOf<LogItem>()
        groups.forEach { group ->
            val lines = mutableListOf<JobLine>()
            group.offers.forEachIndexed { i, offer ->
                lines += JobLine.Accepted(offer.phaseEndedAt!!, offer.dollarsPerHour, offer.dollarsPerMile, i > 0)
            }
            group.pickups.forEach { pickup ->
                pickup.confirmedAt?.let { at -> lines += JobLine.PickedUp(pickup.arrivedAt, at,
                    pickup.arrivedAt?.let { (at - it).coerceAtLeast(0) }) }
            }
            val drops = completed[group.jobId].orEmpty()
            drops.filter { (delivery, _) -> (delivery.jobId to delivery.taskId) !in unassigned }
                .forEach { (delivery, occurredAt) ->
                    val record = records[delivery.taskId]
                    val receipt = group.postTasks.singleOrNull { it.taskId == null || it.taskId == delivery.taskId }
                    val paid = if (record != null) record.realizedPay
                        else receipt?.totalPay?.takeIf { drops.size == 1 }
                    lines += JobLine.Delivered(delivery.completedAt ?: occurredAt, paid)
                }
            // Recover from receipts only when the job has no completion events at all.
            group.postTasks.filter { post -> drops.isEmpty() && (post.jobId to post.taskId) !in unassigned }
                .forEach { post ->
                    val record = records[post.taskId]
                    val paid = if (record != null) record.realizedPay else post.totalPay
                    lines += JobLine.Delivered(post.phaseEndedAt ?: post.phaseStartedAt, paid)
                }
            group.unassignedAt?.let { lines += JobLine.Unassigned(it) }
            val committed = tips.filter { it.jobId == group.jobId }
            committed.forEach { tip ->
                val target = UndoTarget.CashTip(tip.deliverySeq, tip.taskId)
                lines += JobLine.CashTip(tip.at, tip.amount, false, undo?.takeIf { it.target == target })
            }
            pendingTips.filter { pending -> pending.jobId == group.jobId && pending.amount > 0 &&
                committed.none { it.taskId == pending.taskId } }.distinctBy { it.taskId }.forEach { tip ->
                val target = UndoTarget.CashTip(null, tip.taskId)
                lines += JobLine.CashTip(tip.at, tip.amount, true, undo?.takeIf { it.target == target })
            }
            val ordered = lines.sortedWith(compareBy({ it.at }, { lineOrder(it) }))
            val now = if (group.live) live?.nowLine() else null
            val stores = group.pickups.map { it.storeName }.filter { it.isNotBlank() }.distinct()
                .ifEmpty { group.offers.flatMap { it.storeNames }.filter { it.isNotBlank() }.distinct() }
            val score = group.offers.firstOrNull()?.evaluationScore
            rows += JobCardModel(group.jobId, stores.joinToString(" + ").ifBlank { UNKNOWN_STORE },
                score?.toInt(), when {
                    score == null -> ScoreTone.NEUTRAL
                    score >= OfferEvaluator.ACCEPT_THRESHOLD -> ScoreTone.GOOD
                    score <= OfferEvaluator.DECLINE_THRESHOLD -> ScoreTone.BAD
                    else -> ScoreTone.WARN
                }, sumKnown(group.offers.map { it.payAmount }), sumKnown(group.offers.map { it.distanceMiles }),
                ordered + listOfNotNull(now), group.live, ordered.maxOfOrNull { it.at } ?: group.startedAt)
        }
        cards.completed.filterIsInstance<FlowCardSnapshot.Offer>().filter { it.id !in joined }
            .forEach { offer ->
                val outcome = offer.outcome ?: return@forEach
                rows += OfferRow(offer.id, offer.phaseEndedAt ?: offer.phaseStartedAt,
                    offer.storeNames.takeIf { it.isNotEmpty() }?.joinToString(" + "), offer.payAmount, offer.distanceMiles, outcome)
            }
        rows += data.expenses.filter { it.voidedAt == null }.map {
            ExpenseRow(it.eventSequenceId, it.occurredAt, it.category,
                it.note?.takeIf(LogChatFilter::safeText), it.amount)
        }
        rows += data.fuel.filter { it.voidedAt == null }.map {
            FuelRow(it.eventSequenceId, it.occurredAt, it.totalAmount, it.pricePerGallon)
        }
        rows += chat.mapNotNull { msg ->
            LogChatFilter.kept(msg)?.let { DispatcherRow("chat:${msg.id}", msg.timestamp, it) }
        }
        return rows.distinctBy { it.key }.sortedWith(compareBy<LogItem>(
            { it is JobCardModel && it.live }, { it.at }, { itemOrder(it) }, { it.key }))
    }

    private fun sumKnown(values: List<Double?>): Double? =
        if (values.isEmpty() || values.any { it == null }) null else values.sumOf { it!! }

    private fun lineOrder(line: JobLine): Int = when (line) {
        is JobLine.Accepted -> 0; is JobLine.PickedUp -> 1; is JobLine.Delivered -> 2
        is JobLine.Unassigned -> 3; is JobLine.CashTip -> 4; is JobLine.Now -> 5
    }
    private fun itemOrder(item: LogItem): Int = when (item) {
        is JobCardModel -> 0; is OfferRow -> 1; is ExpenseRow -> 2; is FuelRow -> 3; is DispatcherRow -> 4
    }

    private fun StateCardModel.nowLine(): JobLine.Now? = when (this) {
        is StateCardModel.PickupNav -> JobLine.Now(NowPhase.PICKUP_NAV, anchorAt, facts.deadlineAt, facts.pay)
        is StateCardModel.PickupArrived -> JobLine.Now(NowPhase.PICKUP_ARRIVED, arrivedAt, facts.deadlineAt, facts.pay)
        is StateCardModel.Shopping -> JobLine.Now(NowPhase.SHOPPING, arrivedAt, facts.deadlineAt, facts.pay, shopped, total)
        is StateCardModel.DropoffNav -> JobLine.Now(NowPhase.DROPOFF_NAV, anchorAt, facts.deadlineAt, facts.pay)
        is StateCardModel.DropoffArrived -> JobLine.Now(NowPhase.DROPOFF_ARRIVED, arrivedAt, facts.deadlineAt, facts.pay)
        is StateCardModel.Receipt -> JobLine.Now(NowPhase.RECEIPT, deliveredAt, null, null)
        is StateCardModel.Waiting -> null
    }
}
