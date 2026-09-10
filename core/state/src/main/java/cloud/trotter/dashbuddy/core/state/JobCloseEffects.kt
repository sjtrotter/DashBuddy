package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.detectAcceptMismatch
import timber.log.Timber

/**
 * #810 B1 — the job-close accept-reconciliation tripwire, emitted at the effect edge (P1/UDF: the
 * stepper stays pure; this diffs prev/next durable state and emits, the `TASK_UNASSIGNED` precedent).
 *
 * The "mark" the effect diffs is the durable [PlatformRegion.activeJob] transition itself: a job
 * closes when its `jobId` leaves the active slot — cleared to null (T1 retire / PostTask-exit /
 * #736 abandon step-3 / the `endSession` teardown) OR replaced by a fresh `jobId` (T2 close+mint).
 *
 * **#1095 — EVERY close edge is in scope.** v1 additionally required the SESSION to survive the step
 * with the same id, which put the `endSession` teardown (and the same-step stale-end + fresh-mint
 * shape) out of scope entirely. That guard existed for one reason: ATTRIBUTION. A step that commits a
 * stale end (session A) and mints session B in the same breath would have logged A's job against B.
 * So the guard is replaced by the fix it was standing in for — the event and the WARN are attributed
 * to the job's OWN session, `prev.session?.sessionId ?: next.session?.sessionId`, prev first because
 * the closing job lived in the prev session. A dash that ends on top of a stranded accept is exactly
 * the money-losing shape the tripwire exists to make visible (#1078: $21.00, silent, 2026-09-08).
 *
 * **Evidence is MINT-QUALIFIED, not raw.** `endSession` force-stamps `completedAt` on whatever task
 * was active at a bail, which would read as a delivered drop and silence the very close the tripwire
 * now covers. So `next.recentTasks` is masked through the same amdt-#5 discriminator the mint and the
 * #996 completeness proof use ([mintQualified] + the one owner [retirePendingForMint]): an
 * unqualified force-stamp is reverted to unfinished, because a completion with no row is not a
 * delivery. An HONORED teardown (#1078 — the end absorbed a real retire) qualifies and is therefore
 * correctly accounted, so it stays silent.
 *
 * Detection is the pure `:domain` [detectAcceptMismatch]; this only decides the close edge, then logs
 * ONE `JOB_ACCEPT_MISMATCH` (keyed per `jobId`, so at-most-once — a job closes single-shot) + one
 * edge-gated WARN. NO state mutation, NO re-attribution — a tripwire only.
 *
 * **The single-accept floor is lifted only on the LOST-DROP shape (#1095, round 2).** A lone accept
 * closing drop-less is ordinarily the early-offline / coarse-platform class and stays below the
 * detector's `minAccepts` floor of 2. The floor drops to 1 for exactly one shape, named in lifecycle
 * evidence and nothing else (principle 8 — no platform literal): the session ENDED, the job's
 * qualified evidence holds NO completed dropoff, and the job DID reach an ARRIVED dropoff. That is
 * "the dash ended over a drop the dasher stood at, and nothing was ever minted for it". The three
 * shapes it deliberately still ignores: a coarse `task:active` job that never rendered a dropoff at
 * all (no arrived drop → floor 2), a pickup-only teardown (same), and a receipt-backed delivery with
 * no arrival frame (its completion IS qualified → floor 2).
 *
 * `next.recentTasks` is the delivered/unassigned source (a T1 retire completes the drop INTO
 * `recentTasks` before `completeActiveJob`; a T2 close+mint commits it before the fresh mint; the
 * teardown appends the force-stamped or honored task before clearing the slot), unioned inside the
 * detector with the closing job's own `tasks` mirror (where a leftover TBD placeholder lives).
 */
internal fun EffectMap.diffJobClose(
    prev: PlatformRegion,
    next: PlatformRegion,
    obs: Observation,
): List<AppEffect> {
    val closingJob = prev.activeJob ?: return emptyList()
    // No close this step (same job survives, or an add-on `existing.copy` kept the jobId).
    if (next.activeJob?.jobId == closingJob.jobId) return emptyList()

    // #1095: the job's OWN session — prev first, because that is where the closing job lived. This
    // is what the v1 identity guard was really protecting; with the attribution fixed, the guard's
    // only remaining effect was to blind the tripwire to the teardown class.
    val sessionId = prev.session?.sessionId ?: next.session?.sessionId
    val sessionEnded = prev.session != null &&
        next.session?.sessionId != prev.session?.sessionId

    // #1078/#1095: the same mint-qualified view the close-out sweep and the #996 completeness proof
    // read — an unqualified force-stamp is not a delivery.
    val retirePending = prev.retirePendingForMint()
    val evidence = next.recentTasks.map { t ->
        if (mintQualified(prev, retirePending, t)) t else t.copy(completedAt = null)
    }

    // #1095 round 2: the lifted floor names the LOST-DROP shape, not every session end. Both legs
    // are lifecycle evidence — a completed drop in the qualified view (something was minted) and an
    // ARRIVED drop anywhere in the job's lineage (the dasher physically stood at a doorstep).
    val nothingMinted = evidence.none {
        it.jobId == closingJob.jobId && it.phase == TaskPhase.DROPOFF && it.completedAt != null
    }
    val reachedADoorstep = (evidence + closingJob.tasks).any {
        it.jobId == closingJob.jobId && it.phase == TaskPhase.DROPOFF && it.arrivedAt != null
    }
    val lostDropShape = sessionEnded && nothingMinted && reachedADoorstep

    val payload = detectAcceptMismatch(
        closingJob,
        evidence,
        minAccepts = if (lostDropShape) 1 else 2,
    ) ?: return emptyList()

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
