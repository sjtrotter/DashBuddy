package cloud.trotter.dashbuddy.domain.model.state

import cloud.trotter.dashbuddy.domain.evaluation.OfferAction

data class OfferEvaluationEvent(
    val action: OfferAction,
    override val timestamp: Long = System.currentTimeMillis()
) : StateEvent