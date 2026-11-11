package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.ReceiptCoverage
import cloud.trotter.log.gig.domain.state.ReceiptProofGap
import cloud.trotter.log.gig.domain.state.ReceiptScope
import cloud.trotter.log.gig.domain.state.StoreKeys
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.hasCustomerIdentity

/**
 * The drops a receipt read on THIS frame may pay (#1598) — see [ReceiptCoverage].
 *
 * A receipt carries no job identity, so the proof is the platform's own count plus the job's facts. The clauses, in
 * order; the first that fails is the [ReceiptCoverage.gap]:
 *  1. a subject drop was named (`NO_SUBJECT`);
 *  2. a job is live (`NO_LIVE_JOB`). After the close only the SAME receipt episode, already proven while its job was
 *     live, stays proven — the collapsed → commit → late-expand shape. A new episode after the close is never proven:
 *     with a job's task screens all missed the subject falls back to the previous job's last drop;
 *  3. the job holds exactly one accepted offer (`OFFER_COUNT`);
 *  4. that offer's card showed exactly one drop (`CARD_COUNT_MISSING`, `CARD_COUNT_NOT_ONE`);
 *  5. the subject is an accountable drop of the job (`SUBJECT_NOT_ACCOUNTABLE`) and no other drop of the job is
 *     (`OTHER_DROPS`). A never-activated placeholder is not accountable, so it does not block proof: accept mints one
 *     placeholder per merchant row, and a one-customer two-store card would otherwise never prove;
 *  6. the subject's store matches the offer's quoted store through [StoreKeys.normalizedChain] (`STORE_MISSING`,
 *     `STORE_MISMATCH`).
 *
 * Proven covers `{subject}`; anything else covers nothing. Stated residual: a hidden offer whose single drop stands
 * in for this offer's drop after its order vanished unseen. Narrow, and visible through the per-receipt check.
 * Everything unproven is the dasher's to match (#1599) or whole-job settlement's (#1601).
 *
 * Pure: region records only — no wall clock, no `Platform` literal.
 */
internal fun receiptCoverageAt(region: PlatformRegion, subjectTaskId: String?): ReceiptCoverage {
    if (subjectTaskId == null) return unproven(ReceiptProofGap.NO_SUBJECT)
    val job = region.activeJob
    if (job == null) {
        val open = region.openReceiptEvidence
        val sameProvenEpisode =
            open != null && open.subjectTaskId == subjectTaskId && open.lastScope == ReceiptScope.PROVEN_SINGLE
        return if (sameProvenEpisode) proven(subjectTaskId) else unproven(ReceiptProofGap.NO_LIVE_JOB)
    }
    val offer = job.acceptedOffers.singleOrNull() ?: return unproven(ReceiptProofGap.OFFER_COUNT)
    val cardCount = offer.cardDropoffCount ?: return unproven(ReceiptProofGap.CARD_COUNT_MISSING)
    if (cardCount != 1) return unproven(ReceiptProofGap.CARD_COUNT_NOT_ONE)

    // Every copy of the job's tasks: the active task, then the recent list, then the job's own.
    val copies: Map<String, List<Task>> = (
        listOfNotNull(region.activeTask?.takeIf { it.jobId == job.jobId }) +
            region.recentTasks.filter { it.jobId == job.jobId } +
            job.tasks
        ).groupBy { it.taskId }
    val accountable = copies.filterValues { it.isAccountableDrop() }.keys
    if (subjectTaskId !in accountable) return unproven(ReceiptProofGap.SUBJECT_NOT_ACCOUNTABLE)
    if (accountable.any { it != subjectTaskId }) return unproven(ReceiptProofGap.OTHER_DROPS)

    val store = copies.getValue(subjectTaskId).firstNotNullOfOrNull { it.storeName }
        ?: return unproven(ReceiptProofGap.STORE_MISSING)
    val quoted = offer.storeHints.filter(String::isNotBlank).map(StoreKeys::normalizedChain)
    if (StoreKeys.normalizedChain(store) !in quoted) return unproven(ReceiptProofGap.STORE_MISMATCH)
    return proven(subjectTaskId)
}

/** Accountable across copies: a dropoff, identity on some copy, unassigned on none (`Task.isAccountableDropoff`). */
private fun List<Task>.isAccountableDrop(): Boolean =
    any { it.phase == TaskPhase.DROPOFF } && any { it.hasCustomerIdentity } && none { it.unassignedAt != null }

private fun proven(subjectTaskId: String) =
    ReceiptCoverage(taskIds = setOf(subjectTaskId), scope = ReceiptScope.PROVEN_SINGLE)

private fun unproven(gap: ReceiptProofGap) = ReceiptCoverage(scope = ReceiptScope.UNPROVEN, gap = gap)
