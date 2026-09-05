package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
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
    val receipt = flowObs.parsed as? ParsedFields.PostTaskFields ?: return emptyList()
    // ONLY an itemized receipt re-prices — an un-itemized re-render carries no new evidence.
    val parsedPay = receipt.parsedPay ?: return emptyList()
    // Coherence with the stepper: an expanded frame always refreshes `lastPostTaskFields` (the #630
    // downgrade guard only ever skips a COLLAPSED one), so this is a belt, not a second decision.
    if (next.lastPostTaskFields?.parsedPay == null) return emptyList()

    // The job whose completions have already been minted, and what receipt they carried.
    val mark = p.lastClosedJobReceipt ?: return emptyList()
    // It must still be CLOSED on both sides of the step. A re-opened jobId cannot happen (ids are
    // minted monotonically), so this is the fail-closed spelling of "the mint already ran".
    if (p.activeJob?.jobId == mark.jobId || next.activeJob?.jobId == mark.jobId) return emptyList()
    // Nothing is owed when the completions already carried THIS itemization — the marker is the only
    // thing that can tell that apart from a genuinely late expansion, because the close cleared the
    // receipt out of the region.
    val markTotal = mark.totalPay
    val alreadyPriced = mark.itemized && markTotal != null && abs(markTotal - receipt.totalPay) < 0.005
    if (alreadyPriced) return emptyList()

    // The denominator: the job's delivered, accountable dropoffs — the same
    // [Task.isAccountableDropoff] SSOT `DeliveryCompletionEffects.mintingDropoffTasks` filters
    // through, so Σ shares lands on exactly the rows the mint wrote. The amdt-#5 mint qualification
    // needs no re-check here: every one of these completed at or before the close, and the only
    // completion that is force-stamped without minting (an `endSession` bail) also CLEARS the marker.
    val drops = p.recentTasks
        .filter { it.jobId == mark.jobId && it.isAccountableDropoff && it.completedAt != null }
        .distinctBy { it.taskId }
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
