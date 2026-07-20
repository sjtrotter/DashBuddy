package cloud.trotter.dashbuddy.domain.state

import cloud.trotter.dashbuddy.domain.model.event.payload.JobAcceptMismatchPayload

/**
 * #810 B1 — the job-close accept-reconciliation tripwire (pure, `obs.timestamp`-free detection).
 *
 * When a job closes carrying MORE accepted offers than accounted physical orders, an accepted offer
 * silently vanished. The fielded seq-114 shape: two accepts, one delivered drop, one never-activated
 * TBD dropoff placeholder — and ZERO signal, because the unassign committed through a support-chat
 * path that rendered no confirmation screen or click (so no `TASK_UNASSIGNED` ever fired, contrast the
 * #736 help-flow unassign that walks the full confirmation path). This detector makes that seam
 * observable; the emission side ([cloud.trotter.dashbuddy.core.state] `diffJobClose`) fires ONE
 * `JOB_ACCEPT_MISMATCH` event + an edge-gated WARN off its result.
 *
 * **A tripwire, not a guess:** it computes the shape of the mismatch and returns it; it never mutates
 * state, re-attributes pay, or decides which offer died (that attribution is structurally ambiguous —
 * deferred to #810 B2). It is `Platform`-agnostic (operates only on [Job]/[Task] shape) and pure, so
 * it is replay-deterministic.
 *
 * **Accounting** (design point 1):
 * - `nAccepts` = [Job.acceptedOffers]`.size`. Guarded to `>= 2`: a single-accept job that closes
 *   drop-less is the existing early-offline / coarse-platform class (an Uber `task:active` job that
 *   never rendered a drop), NOT this invisible-unassign signal — so it never trips the tripwire.
 * - `accounted` = distinct delivered dropoffs (`completedAt != null && arrivedAt != null &&
 *   unassignedAt == null`, deduped by `taskId`) + distinct unassign-marked orders
 *   (`unassignedAt != null`, deduped by customer identity so a pickup+dropoff leg of the SAME
 *   abandoned order counts once). A properly-#736'd unassign is therefore accounted and does NOT
 *   trip this.
 * - Tasks are the union of `recentTasks` (where delivered + unassigned tasks live) and [Job.tasks]
 *   (the non-unassigned lineage mirror — where a leftover TBD placeholder lives), filtered to this job
 *   and deduped by `taskId`. `recentTasks` is listed FIRST so its FRESH copy of a just-finished drop
 *   wins the dedup over the `Job.tasks` mirror's STALE copy (the mirror re-runs after `stepCore`, so
 *   at a close its copy of the just-finished drop still shows `completedAt` null — the same staleness
 *   `isJobPhysicallyComplete` guards against by deriving completion truth from `recentTasks` only).
 *
 * Returns a fully-built [JobAcceptMismatchPayload] (the payload IS the detection result — one SSOT,
 * no parallel data class) when `nAccepts > accounted`, else null.
 */
fun detectAcceptMismatch(job: Job, recentTasks: List<Task>): JobAcceptMismatchPayload? {
    val nAccepts = job.acceptedOffers.size
    // A single-accept drop-less close is the early-offline / coarse-platform class, not this signal.
    if (nAccepts < 2) return null

    // recentTasks FIRST so a finished drop's fresh copy wins the dedup over the stale Job.tasks mirror.
    val jobTasks = (recentTasks + job.tasks)
        .filter { it.jobId == job.jobId }
        .distinctBy { it.taskId }

    val deliveredDrops = jobTasks.filter {
        it.phase == TaskPhase.DROPOFF &&
            it.completedAt != null &&
            it.arrivedAt != null &&
            it.unassignedAt == null
    }
    // Dedupe unassigned legs by customer identity (a pickup + a dropoff of the same abandoned order
    // must not double-count); fall back to taskId when the abandoned task carries no customer hash.
    val unassignedOrders = jobTasks
        .filter { it.unassignedAt != null }
        .distinctBy { it.customerNameHash ?: it.taskId }

    val accounted = deliveredDrops.size + unassignedOrders.size
    if (nAccepts <= accounted) return null

    // Never-activated TBD dropoff placeholders left outstanding at close — the seq-114 corpse (121).
    val leftoverTbd = jobTasks.count {
        it.phase == TaskPhase.DROPOFF &&
            it.customerNameHash == null &&
            it.completedAt == null &&
            it.unassignedAt == null
    }

    return JobAcceptMismatchPayload(
        jobId = job.jobId,
        acceptedCount = nAccepts,
        accountedCount = accounted,
        acceptedOfferHashes = job.acceptedOffers.mapNotNull { it.offerHash },
        deliveredCustomerHashes = deliveredDrops.mapNotNull { it.customerNameHash },
        leftoverTbdPlaceholders = leftoverTbd,
        unassignedCount = unassignedOrders.size,
    )
}
