package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import kotlinx.serialization.Serializable

/** #823 Phase 2 — the arrival re-evaluation of a single-shop job. Display-only; frozen economics never change. */
@Serializable
data class ArrivalEstimate(
    val taskId: String,
    val offerHash: String?,
    /** itemsRemaining + itemsShopped, read from ONE frame. */
    val observedItems: Int,
    val quotedItems: Int?,
    val correctedHandlingMinutes: Double,
    val correctedEstMinutes: Double,
    /** netPay ÷ (correctedEstMinutes/60), or null when the job carries no net pay. */
    val correctedDollarsPerHour: Double?,
    val computedAt: Long,
)

object ArrivalCorrection {
    /**
     * Eligibility: exactly one accepted shopping offer (no add-on / stack) and one PICKUP task.
     * Count unresolved pickup placeholders too: a second store must not pass just because its
     * activity has not resolved yet. Accept-time isShop survives checkout/confirmed activity.
     */
    fun isEligible(job: Job): Boolean = job.acceptedOffers.singleOrNull()?.isShop == true &&
        job.tasks.count { it.phase == TaskPhase.PICKUP } == 1

    /** The coherent pair from ONE frame, or null: both fields present, both ≥ 0, sum ≥ 1. */
    fun observedItems(fields: ParsedFields.TaskFields): Int? {
        val remaining = fields.itemsRemaining ?: return null
        val shopped = fields.itemsShopped ?: return null
        if (remaining < 0 || shopped < 0) return null
        // Parsed counts are untrusted; do not let an overflowing sum become a plausible count.
        return (remaining.toLong() + shopped).takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }

    /**
     * Swap the accept-time handling term for the arrival-corrected one. Null when the accepted
     * offer carries no [AcceptedOfferEconomics.handlingMinutes] or [AcceptedOfferEconomics.estMinutes]
     * (pre-Phase-2 jobs), or when the result would not be positive.
     * correctedHandling = max(items ÷ pace, base) + nonShopLegs × base;
     * correctedEst = estMinutes − handlingMinutes + correctedHandling.
     */
    fun compute(
        accepted: AcceptedOfferEconomics,
        taskId: String,
        observedItems: Int,
        economy: UserEconomy,
        now: Long,
    ): ArrivalEstimate? {
        val handling = accepted.handlingMinutes ?: return null
        val estimate = accepted.estMinutes ?: return null
        if (observedItems < 1) return null
        val correctedHandling = maxOf(
            observedItems / economy.effectiveShopItemsPerMinute, economy.basePickupMinutes,
        ) + accepted.nonShopLegs * economy.basePickupMinutes
        val correctedEst = estimate - handling + correctedHandling
        if (!correctedEst.isFinite() || correctedEst <= 0.0) return null
        return ArrivalEstimate(
            taskId = taskId,
            offerHash = accepted.offerHash,
            observedItems = observedItems,
            quotedItems = null, // AcceptedOfferEconomics preserves units, not a quoted item count.
            correctedHandlingMinutes = correctedHandling,
            correctedEstMinutes = correctedEst,
            correctedDollarsPerHour = accepted.netPay?.let { it / (correctedEst / 60.0) },
            computedAt = now,
        )
    }
}
