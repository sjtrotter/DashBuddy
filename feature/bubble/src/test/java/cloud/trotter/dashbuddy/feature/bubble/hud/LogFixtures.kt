package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.analytics.*
import cloud.trotter.dashbuddy.domain.model.cards.*
import cloud.trotter.dashbuddy.domain.model.chat.*
import cloud.trotter.dashbuddy.domain.model.event.*
import cloud.trotter.dashbuddy.domain.model.event.payload.*
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.TaskPhase

internal fun logOffer(hash: String = "offer", at: Long = 1_000, outcome: AppEventType = AppEventType.OFFER_ACCEPTED) =
    FlowCardSnapshot.Offer(at - 100, at, hash, payAmount = 10.6, distanceMiles = 4.2,
        storeNames = listOf("Panera Bread"), evaluationScore = 81.0, dollarsPerHour = 16.0, dollarsPerMile = 1.93,
        outcome = outcome)
internal fun logPickup(job: String = "job", task: String = "pickup", start: Long = 2_000) =
    FlowCardSnapshot.Pickup(start, start + 2_000, task, job, "Panera Bread", arrivedAt = start + 1_000, confirmedAt = start + 2_000)
internal fun logDelivery(job: String = "job", task: String = "drop", start: Long = 4_000) =
    FlowCardSnapshot.Delivery(start, start + 4_000, task, job, storeName = "Panera Bread", arrivedAt = start + 3_000)
internal fun logPost(job: String = "job", task: String = "drop", at: Long = 9_000) =
    FlowCardSnapshot.PostTask(at - 2_000, at, job, task, "Panera Bread", totalPay = 10.6)
internal fun logStack(live: Boolean = false): CardStack {
    val history = listOf(logOffer(), logPickup())
    return if (live) CardStack(history, logDelivery().copy(phaseEndedAt = null))
    else CardStack(history + logDelivery() + logPost())
}
internal fun logLive() = StateCardModel.DropoffArrived(JobFacts("Panera Bread", 15_000, 8.1, 4.2), 7_000)
internal fun logRecord(seq: Long = 10, job: String = "job", task: String = "drop", cash: Double? = 5.0) =
    DeliveryRecord(seq, "dash", Platform.DoorDash, job, task, "Panera Bread", 9_000,
        realizedPay = 10.6, payBasis = "DROP_SHARE", netProfit = 8.1, realizedMiles = 4.2,
        realizedMinutes = 20.0, tip = 5.0, basePay = 5.6, cashTip = cash)
internal fun logExpense(seq: Long = 20, at: Long = 5_000) =
    ExpenseRecord(seq, "dash", at, ExpenseCategory.FOOD, 11.4, null, null)
internal fun logFuel(seq: Long = 30, at: Long = 6_000, gallons: Double? = 10.0) =
    FuelRecord(seq, "dash", at, 31.9, gallons, null, null)
internal fun logAdjustment(target: Long = 10, at: Long = 10_000, amount: Double? = 5.0) =
    AppEvent(AppEventType.DELIVERY_ADJUSTMENT, at, "dash", DeliveryAdjustmentPayload(target, "dash", newCashTip = amount))
internal fun logLink(job: String = "job", hashes: List<String> = listOf("offer"), start: Long = 2_000) =
    AppEvent(AppEventType.PICKUP_NAV_STARTED, start, "dash",
        PickupPayload(job, "pickup", "Panera Bread", start, jobOfferHashes = hashes))
internal fun logUnassigned(job: String = "job", at: Long = 10_000) =
    AppEvent(AppEventType.TASK_UNASSIGNED, at, "dash",
        TaskUnassignedPayload(job, "pickup", TaskPhase.PICKUP, "Panera Bread", startedAt = 2_000,
            unassignedAt = at, jobOfferHashes = listOf("offer")))
internal fun logChat(text: String, persona: ChatPersona = ChatPersona.Dispatcher, id: String = "line", at: Long = 500) =
    ChatMessage(id, "dash", text, at, persona)
