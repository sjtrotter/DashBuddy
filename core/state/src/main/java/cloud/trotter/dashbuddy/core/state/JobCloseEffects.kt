package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.detectAcceptMismatch
import timber.log.Timber

/**
 * #810 B1 — the job-close accept-reconciliation tripwire, emitted at the effect edge (P1/UDF: the
 * stepper stays pure; this diffs prev/next durable state and emits, the `TASK_UNASSIGNED` precedent).
 *
 * The "mark" the effect diffs is the durable [PlatformRegion.activeJob] transition itself: a job
 * closes when its `jobId` leaves the active slot — cleared to null (T1 retire / PostTask-exit /
 * #736 abandon step-3) OR replaced by a fresh `jobId` (T2 close+mint). ALL of those in-scope close
 * paths route through `PlatformRegionStepper.completeActiveJob`. [endSession] ALSO clears
 * `activeJob`, but it uniquely nulls the session in the same step; the `next.session == null` guard
 * keeps it out of scope (v1 — an offline-mid-job dash is its own class; #810 B1, noted in-code).
 *
 * Detection is the pure `:domain` [detectAcceptMismatch]; this only decides the close edge, then logs
 * ONE `JOB_ACCEPT_MISMATCH` (keyed per `jobId`, so at-most-once — a job closes single-shot) + one
 * edge-gated WARN. NO state mutation, NO re-attribution — a tripwire only.
 *
 * `next.recentTasks` is the delivered/unassigned source (a T1 retire completes the drop INTO
 * `recentTasks` before `completeActiveJob`; a T2 close+mint commits it before the fresh mint), unioned
 * inside the detector with the closing job's own `tasks` mirror (where a leftover TBD placeholder
 * lives).
 */
internal fun EffectMap.diffJobClose(
    prev: PlatformRegion,
    next: PlatformRegion,
    obs: Observation,
): List<AppEffect> {
    val closingJob = prev.activeJob ?: return emptyList()
    // No close this step (same job survives, or an add-on `existing.copy` kept the jobId).
    if (next.activeJob?.jobId == closingJob.jobId) return emptyList()
    // endSession's simultaneous session-null is the out-of-scope marker (v1).
    if (next.session == null) return emptyList()

    val payload = detectAcceptMismatch(closingJob, next.recentTasks) ?: return emptyList()

    val sessionId = next.session?.sessionId ?: prev.session?.sessionId
    return buildList {
        // WARN (P7): counts + jobId + hash PREFIXES only — no store/customer/raw text. Stable tag,
        // matching the #691/#699 D6 join-miss precedent (the state module logs under "StateMachine").
        Timber.tag("StateMachine").w(
            "#810 job-close accept mismatch: job %s — %d accepts > %d accounted " +
                "(%d leftover TBD, %d unassigned); offers=%s delivered=%s",
            payload.jobId,
            payload.acceptedCount,
            payload.accountedCount,
            payload.leftoverTbdPlaceholders,
            payload.unassignedCount,
            payload.acceptedOfferHashes.map { it.take(6) },
            payload.deliveredCustomerHashes.map { it.take(6) },
        )
        add(
            logEffect(
                sessionId,
                AppEventType.JOB_ACCEPT_MISMATCH,
                obs.timestamp,
                payload,
                effectKeyOverride = "log:${AppEventType.JOB_ACCEPT_MISMATCH}:${payload.jobId}",
            ),
        )
    }
}
