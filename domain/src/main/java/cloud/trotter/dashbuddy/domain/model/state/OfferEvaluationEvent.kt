package cloud.trotter.dashbuddy.domain.model.state

import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation

data class OfferEvaluationEvent(
    val action: OfferAction,
    val evaluation: OfferEvaluation? = null,
    /** Hash of the offer this evaluation was computed FOR — correlated by the stepper (#345). */
    val offerHash: String? = null,
    override val timestamp: Long = System.currentTimeMillis()
) : StateEvent
