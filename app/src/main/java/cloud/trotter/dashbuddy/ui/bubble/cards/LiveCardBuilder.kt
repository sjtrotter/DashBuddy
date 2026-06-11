package cloud.trotter.dashbuddy.ui.bubble.cards

import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.UNKNOWN_STORE

/**
 * Constructs the live (active) flow card from the current [AppState].
 *
 * Unlike [FlowCardMapper.fold], which folds the persisted event log into
 * completed-card snapshots, this builds the single live card that sits
 * pinned at the bottom of the bubble HUD stack, expanded and ticking. It
 * reads from in-memory state (the focused [PlatformRegion] + flow region)
 * and produces a snapshot whose [FlowCardSnapshot.phaseEndedAt] is null.
 */
object LiveCardBuilder {

    fun build(state: AppState): FlowCardSnapshot? {
        val platform = state.regions.flow.activePlatform
            ?: state.regions.crossPlatform.mostRecentActivityPlatform
            ?: return null
        val region = state.regions.platforms[platform] ?: return null
        val flow = state.regions.flow.flow
        val now = state.timestamp

        return when (flow) {
            Flow.Idle -> {
                if (region.mode != Mode.Online) return null
                val started = region.idleEnteredAt ?: region.session?.startedAt ?: return null
                FlowCardSnapshot.Awaiting(
                    id = "awaiting:${region.session?.sessionId ?: "?"}:$started",
                    phaseStartedAt = started,
                    sessionId = region.session?.sessionId,
                )
            }

            Flow.OfferPresented -> {
                val pending = state.regions.flow.pendingOffer ?: return null
                val offer = pending.offerFields.parsedOffer
                FlowCardSnapshot.Offer(
                    phaseStartedAt = pending.presentedAt,
                    offerHash = pending.offerHash,
                    payAmount = offer.payAmount,
                    distanceMiles = offer.distanceMiles,
                    itemCount = offer.itemCount,
                    storeNames = offer.orders.map { it.storeName }.distinct(),
                    evaluationScore = pending.evaluation?.score,
                    evaluationAction = pending.evaluation?.action?.name,
                    netPayAmount = pending.evaluation?.netPayAmount,
                    dollarsPerMile = pending.evaluation?.dollarsPerMile,
                    dollarsPerHour = pending.evaluation?.dollarsPerHour,
                    qualityLevel = pending.evaluation?.qualityLevel,
                    badges = (offer.badges.map { it.name } +
                        offer.orders.flatMap { it.badges }.map { it.name }).distinct(),
                    expiresAt = offer.initialCountdownSeconds?.let { pending.presentedAt + it * 1000L },
                    countdownSeconds = offer.initialCountdownSeconds,
                    outcome = null,
                )
            }

            Flow.TaskPickupNavigation, Flow.TaskPickupArrived -> {
                val task = region.activeTask ?: return null
                if (task.phase != TaskPhase.PICKUP) return null
                FlowCardSnapshot.Pickup(
                    phaseStartedAt = task.startedAt,
                    taskId = task.taskId,
                    jobId = task.jobId,
                    storeName = task.storeName ?: UNKNOWN_STORE,
                    arrivedAt = task.arrivedAt,
                    deadlineMillis = task.deadlineMillis,
                    itemsRemaining = task.itemsRemaining,
                    itemsShopped = task.itemsShopped,
                    activity = task.activity,
                )
            }

            Flow.TaskDropoffNavigation, Flow.TaskDropoffArrived -> {
                val task = region.activeTask ?: return null
                if (task.phase != TaskPhase.DROPOFF) return null
                FlowCardSnapshot.Delivery(
                    phaseStartedAt = task.startedAt,
                    taskId = task.taskId,
                    jobId = task.jobId,
                    storeName = task.storeName,
                    customerHash = task.customerNameHash,
                    arrivedAt = task.arrivedAt,
                    deadlineMillis = task.deadlineMillis,
                )
            }

            Flow.PostTask -> {
                val activeTask = region.activeTask ?: region.recentTasks.lastOrNull()
                val pt = region.lastPostTaskFields
                FlowCardSnapshot.PostTask(
                    phaseStartedAt = activeTask?.completedAt
                        ?: activeTask?.arrivedAt
                        ?: now,
                    jobId = activeTask?.jobId ?: region.activeJob?.jobId ?: "unknown",
                    taskId = activeTask?.taskId,
                    storeName = activeTask?.storeName,
                    totalPay = pt?.totalPay ?: 0.0,
                    parsedPay = pt?.parsedPay,
                    sessionEarningsAtCompletion = pt?.sessionEarnings,
                )
            }

            Flow.SessionEnded -> null
        }
    }
}
