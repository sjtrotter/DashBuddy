package cloud.trotter.log.gig.core.state.model

import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion

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
)

/** Invocation-local accumulator, explicitly passed through the decision-producing paths. */
internal class DecisionSink {
    private var receiptReprice: ReceiptRepriceDecision? = null
    private var retiredTaskId: String? = null

    fun reprice(d: ReceiptRepriceDecision) { receiptReprice = d }
    fun retired(taskId: String) { retiredTaskId = taskId }
    fun build(): RegionDecisions = RegionDecisions(receiptReprice, retiredTaskId)
}

data class PlatformStep(val region: PlatformRegion, val decisions: RegionDecisions)
