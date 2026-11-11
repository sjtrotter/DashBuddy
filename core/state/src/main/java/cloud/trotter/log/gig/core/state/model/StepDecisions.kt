package cloud.trotter.log.gig.core.state.model

import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.ReceiptProofGap
import cloud.trotter.log.gig.domain.state.ReceiptScope

/** Decisions made by one step that its before/after states cannot show. Never persisted. */
data class StepDecisions(val byPlatform: Map<Platform, RegionDecisions> = emptyMap()) {
    fun of(p: Platform): RegionDecisions = byPlatform[p] ?: RegionDecisions.NONE
    companion object { val NONE = StepDecisions() }
}

data class RegionDecisions(
    /** Last re-price written in the step wins. */
    val receiptReprice: ReceiptRepriceDecision? = null,
    /** The active task this step retired as a delivery. */
    val retiredTaskId: String? = null,
    /** #1598: the receipt evidence revision this step recorded; last wins. */
    val receiptEvidence: ReceiptEvidenceDecision? = null,
) {
    companion object { val NONE = RegionDecisions() }
}

data class ReceiptRepriceDecision(
    val jobId: String,
    val parsedPay: ParsedPay,
    val shares: Map<String, Double>,
    val decidedAt: Long,
    val revision: Int,
    val sourceCaptureId: String? = null,
    /** #1598: the evidence record of the receipt this decision priced from. */
    val evidenceId: String? = null,
)

/** #1598: one read of a post-delivery receipt, saved as evidence (`RECEIPT_OBSERVED`). Moves no money. */
data class ReceiptEvidenceDecision(
    val evidenceId: String,
    val revision: Int,
    val platformWire: String,
    val jobId: String,
    val subjectTaskId: String,
    val firstSeenAt: Long,
    val observedAt: Long,
    val totalPay: Double,
    val parsedPay: ParsedPay?,
    val isExpanded: Boolean,
    val scope: ReceiptScope,
    val gap: ReceiptProofGap?,
    val sourceCaptureId: String?,
)

/** Invocation-local accumulator, explicitly passed through the decision-producing paths. */
internal class DecisionSink {
    private var receiptReprice: ReceiptRepriceDecision? = null
    private var retiredTaskId: String? = null
    private var receiptEvidence: ReceiptEvidenceDecision? = null

    fun reprice(d: ReceiptRepriceDecision) { receiptReprice = d }
    fun retired(taskId: String) { retiredTaskId = taskId }
    fun receiptObserved(d: ReceiptEvidenceDecision) { receiptEvidence = d }
    fun build(): RegionDecisions = RegionDecisions(receiptReprice, retiredTaskId, receiptEvidence)
}

data class PlatformStep(val region: PlatformRegion, val decisions: RegionDecisions)
