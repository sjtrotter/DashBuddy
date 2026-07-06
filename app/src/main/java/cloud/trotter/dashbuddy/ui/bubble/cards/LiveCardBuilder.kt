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
                // #438 B3: the foreground offer is the focused platform's own presented offer
                // (offers moved off the shared global R0 slot onto the owning region).
                val pending = region.presentedOffer() ?: return null
                val offer = pending.offerFields.parsedOffer
                FlowCardSnapshot.Offer.from(
                    parsedOffer = offer,
                    evaluation = pending.evaluation,
                    offerHash = pending.offerHash,
                    phaseStartedAt = pending.presentedAt,
                    // Live card derives the countdown anchors so the expiry bar can tick.
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
                    netPay = region.activeJob?.blendedNetPay,
                    estMinutes = region.activeJob?.blendedEstMinutes,
                    distanceMiles = region.activeJob?.blendedDistanceMiles,
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
                    netPay = region.activeJob?.blendedNetPay,
                    estMinutes = region.activeJob?.blendedEstMinutes,
                    distanceMiles = region.activeJob?.blendedDistanceMiles,
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
