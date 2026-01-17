package cloud.trotter.dashbuddy.state.event

import cloud.trotter.dashbuddy.state.model.OfferAction

data class OfferEvaluationEvent(
    val action: OfferAction,
    override val timestamp: Long = System.currentTimeMillis()
) : StateEvent