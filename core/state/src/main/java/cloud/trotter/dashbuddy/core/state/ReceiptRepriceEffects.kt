package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.DropPayApportioner
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.isAccountableDropoff
import timber.log.Timber
import kotlin.math.abs

/**
 * #1033 layer 2 — the machine's own Tier-1 receipt correction, extracted as its own file beside
 * [DeliveryCompletionEffects] (the `internal` extension-on-[EffectMap] convention [OfferEffects] /
 * [JobAcceptFlow] set).
 *
 * **The seam.** A COLLAPSED post-delivery receipt carries a total but no itemization, so
 * `DropPayApportioner.apportion(parsedPay = null, …)` returns nothing and the `DELIVERY_COMPLETED`
 * it closes is priced by the #691 `OFFER_PAY` estimate. Layer 1 widens the retire grace so the
 * expansion usually lands *before* the commit; this is the other half — when it lands *after*, the
 * itemization is real evidence that arrived late, and the drop is re-priced from it rather than left
 * on an estimate forever.
 *
 * **Append-only, exactly like a driver correction.** No completion event is rewritten: a new
 * `DELIVERY_RECEIPT_REPRICE` per delivered drop of the job carries the receipt + that drop's share
 * (from the SAME [DropPayApportioner.apportion] the mint uses, over the SAME denominator), and the
 * fold re-prices the row in place. Frozen economy is never re-costed — net recomputes against the
 * row's OWN `frozenCostPerMile` at the orchestrator.
 *
 * **Why it can fire at all:** `completeActiveJob` clears `lastPostTaskFields`, so the region alone
 * cannot say what the completions carried; [PlatformRegion.lastClosedJobReceipt] is the minimal
 * marker recording exactly that, stamped at the one close site.
 *
 * Platform-agnostic (Principle 8): every input is this region's own records — no `Platform` literal,
 * no wire string. Pure: `obs.timestamp`-driven, no wall clock, no side effect beyond the emitted
 * log effects.
 */
internal fun EffectMap.diffReceiptReprice(
    p: PlatformRegion,
    next: PlatformRegion,
    actedNextFlow: Flow,
    obs: Observation,
): List<AppEffect> {
    // #438 item 5: gate on THIS region's own acted flow — a foreign platform's receipt frame must
    // never re-price this region's drops.
    if (actedNextFlow != Flow.PostTask) return emptyList()
    val flowObs = obs as? Observation.FlowObservation ?: return emptyList()
    // The frame must be THIS region's own (#1033 review round 3). `EffectMap.diff` visits every
    // platform region on every observation, and a region that did not act keeps `p === next` — but
    // `actedNextFlow` falls back to the shared global R0 flow while `lastActedFlow` is null, so a
    // foreign platform's receipt frame could reach this. Defensive today (no Uber rule parses a
    // receipt), structural tomorrow.
    if (flowObs.platform != next.platform) return emptyList()
    val receipt = flowObs.parsed as? ParsedFields.PostTaskFields ?: return emptyList()
    // ONLY an itemized receipt re-prices — an un-itemized re-render carries no new evidence.
    val parsedPay = receipt.parsedPay ?: return emptyList()
    // Coherence with the stepper: an expanded frame always refreshes `lastPostTaskFields` (the #630
    // downgrade guard only ever skips a COLLAPSED one), so this is a belt, not a second decision.
    if (next.lastPostTaskFields?.parsedPay == null) return emptyList()

    // The job whose completions have already been minted, and what receipt they carried.
    //
    // Read from the RESULTING region (#1033 review R3), not the prior one: an expansion whose own
    // frame is ALSO the one that trips the collapsed grace's lazy expiry closes the job on this very
    // step, so `p.lastClosedJobReceipt` is still null while `next`'s is the marker this frame just
    // stamped. `p` was the blind spot — that ordering (expiry, then the receipt store) is the normal
    // shape for an expansion arriving a hair past the deadline, and FrameGate suppresses the
    // identical later renders, so nothing would ever have corrected it. On every other frame the two
    // are the same value. The marker's `itemized`/`totalPay` describe the receipt as it stood AT the
    // close (the expiry runs before `updateSessionFields` stores this frame's parse), so the
    // `alreadyPriced` check below still compares the completion's receipt against the new one.
    val mark = next.lastClosedJobReceipt ?: return emptyList()
    // No job may be live AFTER this step, and the only job that may have been live BEFORE it is the
    // marker's own — i.e. the one closing on this very frame (#1033 review R1 + R3 together).
    //
    // R1: the re-price window is strictly between a close and the next job's mint.
    // `lastAnnouncedPostTaskTaskId` falls back to `recentTasks.lastOrNull()`, so a live job whose
    // task screens were all MISSED would otherwise have its receipt anchored on the PREVIOUS job's
    // last drop and its money appended as a re-price of that job. The stepper's
    // `clearClosedJobReceiptOnNewJob` closes the same hole from the state side; this is the
    // emitter's own half, and it also covers the frames before a new job's mint lands.
    //
    // R3: a flat "no job on either side" would ALSO reject the same-frame close — the expansion that
    // itself trips the collapsed grace's lazy expiry, where `p.activeJob` is still the job the
    // marker names. That frame is the whole point of reading the marker from `next`, so the prior
    // side is checked by IDENTITY, not by emptiness. Nothing else can reach this line with
    // `p.activeJob` set: a job still open in `next` already returned above.
    if (next.activeJob != null) return emptyList()
    val priorJob = p.activeJob
    if (priorJob != null && priorJob.jobId != mark.jobId) return emptyList()
    // Nothing is owed when the completions already carried THIS itemization — the marker is the only
    // thing that can tell that apart from a genuinely late expansion, because the close cleared the
    // receipt out of the region.
    val markTotal = mark.totalPay
    // Does the receipt on screen carry the SAME total the closed job's own receipt showed? On 8.93.7
    // the #1029 collapsed parse yields `totalPay` even with no itemization, so this is a POSITIVE
    // identity: it says the frame is a re-render of THAT job's receipt, which a different job's
    // receipt cannot satisfy except by a coincidence of totals — and a coincidence re-prices with the
    // identical total, so the itemization split changes and the money does not (accepted).
    val ownershipByTotal = markTotal != null && abs(markTotal - receipt.totalPay) < 0.005
    val alreadyPriced = mark.itemized && ownershipByTotal
    if (alreadyPriced) return emptyList()

    // Once an offer has been ACCEPTED since the close, "no job is live" stops being evidence of
    // ownership (#1033 review round 3): the accept and the job mint are separate transitions, so a
    // next job whose task screens were all missed never sets `activeJob` at all, and its receipt
    // would otherwise be re-priced onto this one. From that point a re-price needs the positive
    // same-total identity above.
    //
    // This is exactly what preserves the COMMON stacked case: the dasher accepts the next offer while
    // the previous delivery's receipt is still up, then expands that receipt — same total, so it
    // re-prices normally. What it refuses is the ambiguous shape: a DIFFERENT total after an accept
    // (which job's receipt is this?), and — fail-null — a marker whose collapsed parse yielded NO
    // total at all, where there is no identity to check.
    if (mark.acceptedSince && !ownershipByTotal) return emptyList()

    // The denominator, rebuilt as the mint built it — `Task.isAccountableDropoff` (the #498 phantom +
    // #736 unassign firewalls) plus the SAME amdt-#5 [mintQualified] predicate
    // `DeliveryCompletionEffects.mintingDropoffTasks` applies, so Σ shares lands on exactly the rows
    // the mint wrote.
    //
    // The active task is a CANDIDATE, not an afterthought (#1033 review R2): the PostTask-exit mint
    // can complete a task that is STILL ACTIVE under its retire grace — `completedAt` is stamped only
    // when that grace commits — so a plain `recentTasks + completedAt != null` scan finds an empty
    // denominator for exactly the job whose receipt is on screen, and the re-price silently never
    // fires (the later `TASK_RETIRE` timer moves the task, but a timer is not a receipt observation,
    // so no frame is coming to retry). [mintQualified] is what makes including it safe: it admits the
    // active task only while a `TASK_RETIRE` really is pending, which is the same evidence the mint
    // required of it.
    val retirePending = p.pendingDestructive?.kind == DestructiveKind.TASK_RETIRE
    val drops = (p.recentTasks + listOfNotNull(p.activeTask))
        .filter { it.jobId == mark.jobId && it.isAccountableDropoff }
        .distinctBy { it.taskId }
        .filter { mintQualified(p, retirePending, it) }
    if (drops.isEmpty()) return emptyList()

    // The receipt on screen must belong to THAT job — the stepper's announce anchor names the task
    // the receipt was attributed to. A receipt anchored on some other job's drop (or on a pickup) is
    // not evidence about these rows: fail-null.
    val announceId = next.lastAnnouncedPostTaskTaskId ?: return emptyList()
    if (drops.none { it.taskId == announceId }) return emptyList()

    val shares = DropPayApportioner.apportion(parsedPay, drops)
    if (shares.isEmpty()) return emptyList()

    val sessionId = next.session?.sessionId ?: p.session?.sessionId
    // Idempotency: one durable `effects_fired` row per (taskId, itemization). A repeat frame carrying
    // the same receipt is dropped by the engine's dedup; a genuinely DIFFERENT itemization (a tip
    // added after the fact) is a new key and re-prices again, which is the correct behaviour.
    val payHash = parsedPay.hashCode()
    val effects = drops.mapNotNull { task ->
        val share = shares[task.taskId] ?: return@mapNotNull null
        logEffect(
            sessionId,
            AppEventType.DELIVERY_RECEIPT_REPRICE,
            obs.timestamp,
            DeliveryReceiptRepricePayload(
                jobId = mark.jobId,
                taskId = task.taskId,
                totalPay = parsedPay.total,
                parsedPay = parsedPay,
                dropRealizedPay = share,
                sourceCaptureId = flowObs.captureId,
            ),
            effectKeyOverride = "log:${AppEventType.DELIVERY_RECEIPT_REPRICE}:${task.taskId}:$payHash",
        )
    }
    if (effects.isNotEmpty()) {
        // P7: ids + cents only — no store/customer text. One line per job per emitting frame; the
        // durable dedup makes a repeat frame silent.
        Timber.tag("StateMachine").d(
            "#1033 receipt re-price: job %s, %d drop(s), receipt %d¢ (completion was %s)",
            mark.jobId,
            effects.size,
            Math.round(parsedPay.total * 100.0),
            if (mark.itemized) "itemized at a different total" else "un-itemized",
        )
    }
    return effects
}
