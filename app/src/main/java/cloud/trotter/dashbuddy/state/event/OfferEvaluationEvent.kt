package cloud.trotter.dashbuddy.state.event

import cloud.trotter.dashbuddy.domain.evaluation.OfferAction

data class OfferEvaluationEvent(
    val action: OfferAction,
    override val timestamp: Long = System.currentTimeMillis()
) : StateEvent