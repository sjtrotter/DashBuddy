package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.core.state.model.DecisionSink
import cloud.trotter.log.gig.core.state.model.ReceiptEvidenceDecision
import cloud.trotter.log.gig.core.state.model.RegionDecisions
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptObservedPayload
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.OpenReceiptEvidence
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.ReceiptCoverage
import cloud.trotter.log.gig.domain.state.ReceiptScope
import timber.log.Timber

/**
 * #1598 — record one read of a post-delivery receipt as evidence ([ReceiptEvidenceDecision] → `RECEIPT_OBSERVED`).
 * Evidence moves no money; it is what the dasher's matching sheet (#1599) and the per-receipt check read.
 *
 * Admitted only for a pay-bearing receipt (the #691 predicate: itemized, or a positive total) with a named subject,
 * read in a session that owns the receipt's job (`ownsReceiptJob`, #1103).
 *
 * **Identity: one record per (receipt episode, subject).** The episode is [PlatformRegion.openReceiptEvidence],
 * kept while the flow stays on the receipt or its offer overlay ([receiptEpisodeContinues]). Within it a changed
 * read — another total, a new itemization, or another proof scope — is a new REVISION of the same record, so a
 * collapsed receipt that expands updates its record rather than adding one; an unchanged re-render (or a collapsed
 * re-read at the same total) records nothing. Equal totals are never identity: a different subject or a broken
 * episode is a new record, and repeated reads of one receipt across episodes are separate records that #1599's
 * "Same receipt as…" links.
 *
 * Pure: `obs.timestamp` only. The id is `receipt:<subject>:<firstSeenAt>`, so a recovery replay re-derives it.
 */
internal fun observeReceiptEvidence(
    sink: DecisionSink,
    region: PlatformRegion,
    parsed: ParsedFields.PostTaskFields,
    subjectTaskId: String?,
    coverage: ReceiptCoverage,
    obs: Observation,
): PlatformRegion {
    if (region.session == null || !region.ownsReceiptJob() || subjectTaskId == null) return region
    if (parsed.parsedPay == null && parsed.totalPay <= 0.0) return region
    val jobId = (listOfNotNull(region.activeTask) + region.recentTasks + region.activeJob?.tasks.orEmpty())
        .firstOrNull { it.taskId == subjectTaskId }?.jobId ?: return region
    val cents = Formats.cents(parsed.totalPay)
    val scope = coverage.scope ?: ReceiptScope.UNPROVEN

    val open = region.openReceiptEvidence?.takeIf { it.subjectTaskId == subjectTaskId }
    val next = if (open == null) {
        OpenReceiptEvidence(
            evidenceId = "receipt:$subjectTaskId:${obs.timestamp}",
            subjectTaskId = subjectTaskId,
            jobId = jobId,
            firstSeenAt = obs.timestamp,
            revision = 1,
            lastTotalCents = cents,
            lastParsedPay = parsed.parsedPay,
            lastScope = scope,
            possibleWorkSeqAtOpen = region.possibleWorkSeq,
        )
    } else {
        val changed = cents != open.lastTotalCents ||
            (parsed.parsedPay != null && parsed.parsedPay != open.lastParsedPay) ||
            scope != open.lastScope
        if (!changed) return region
        open.copy(
            revision = open.revision + 1,
            lastTotalCents = cents,
            lastParsedPay = parsed.parsedPay ?: open.lastParsedPay,
            lastScope = scope,
        )
    }
    sink.receiptObserved(
        ReceiptEvidenceDecision(
            evidenceId = next.evidenceId,
            revision = next.revision,
            platformWire = region.platform.wire,
            jobId = next.jobId,
            subjectTaskId = subjectTaskId,
            firstSeenAt = next.firstSeenAt,
            observedAt = obs.timestamp,
            totalPay = parsed.totalPay,
            parsedPay = parsed.parsedPay,
            isExpanded = parsed.isExpanded,
            scope = scope,
            gap = coverage.gap,
            sourceCaptureId = obs.captureId,
        ),
    )
    return region.copy(openReceiptEvidence = next)
}

/**
 * #1460 / #1598: does the receipt episode survive a frame whose flow is [next], reached from [prevFlow]? Only the
 * receipt itself, or the offer overlay reached straight from the receipt (or from the overlay), continues it. The
 * one continuity rule the settle gate's `receiptEpisodeKey` and the evidence episode share.
 */
internal fun receiptEpisodeContinues(prevFlow: Flow, next: Flow): Boolean =
    next == Flow.PostTask ||
        (next == Flow.OfferPresented && (prevFlow == Flow.PostTask || prevFlow == Flow.OfferPresented))

/**
 * #1598: clear [PlatformRegion.openReceiptEvidence] on a flow-bearing frame that does not continue the episode
 * ([receiptEpisodeContinues]). A flow-less observation (a timer, a click) leaves it alone. Called from the step
 * wrapper, so no early return skips it.
 */
internal fun receiptEvidenceEpisode(region: PlatformRegion, prevFlow: Flow, obs: Observation): PlatformRegion {
    val next = (obs as? Observation.FlowObservation)?.flow ?: return region
    if (region.openReceiptEvidence == null || receiptEpisodeContinues(prevFlow, next)) return region
    return region.copy(openReceiptEvidence = null)
}

/**
 * Emit what [observeReceiptEvidence] recorded: one `RECEIPT_OBSERVED` keyed by evidence id and revision, so a
 * replayed step dedups and an X → Y → X itemization keeps three distinct keys.
 */
internal fun EffectMap.diffReceiptEvidence(
    p: PlatformRegion,
    next: PlatformRegion,
    decisions: RegionDecisions,
): List<AppEffect> {
    val d = decisions.receiptEvidence ?: return emptyList()
    // P7: ids, cents and enum names only — never store or customer text.
    Timber.tag("StateMachine").d(
        "#1598 receipt evidence %s r%d: %d¢, %s%s, job %s, subject %s",
        d.evidenceId, d.revision, Formats.cents(d.totalPay), d.scope.name,
        if (d.scope == ReceiptScope.UNPROVEN) "/${d.gap?.name}" else "", d.jobId, d.subjectTaskId,
    )
    return listOf(
        logEffect(
            next.session?.sessionId ?: p.session?.sessionId,
            AppEventType.RECEIPT_OBSERVED,
            d.observedAt,
            ReceiptObservedPayload(
                evidenceId = d.evidenceId,
                revision = d.revision,
                platform = d.platformWire,
                jobId = d.jobId,
                subjectTaskId = d.subjectTaskId,
                firstSeenAt = d.firstSeenAt,
                observedAt = d.observedAt,
                totalPay = d.totalPay,
                parsedPay = d.parsedPay,
                isExpanded = d.isExpanded,
                scope = d.scope,
                proofGap = d.gap,
                sourceCaptureId = d.sourceCaptureId,
            ),
            effectKeyOverride = "log:${AppEventType.RECEIPT_OBSERVED}:${d.evidenceId}:r${d.revision}",
        ),
    )
}
