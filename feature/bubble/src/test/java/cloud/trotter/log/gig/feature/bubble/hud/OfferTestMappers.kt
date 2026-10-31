package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.offer.story.*
import cloud.trotter.log.gig.domain.evaluation.*
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.state.*
import cloud.trotter.log.gig.domain.format.Formats

internal fun mapOffer(pending: PendingOffer, job: Job?, config: EvaluationConfig?,
    evaluate: (ParsedOffer, EvaluationConfig) -> OfferEvaluation = OfferEvaluator()::evaluate) =
    OfferCardMapper.map(OfferStories.build(pending, job, config, pending.platform, evaluate = evaluate), pending, job)

internal fun mapGauge(kind: GaugeKind, denominator: Double?, rate: Double?, target: Double?,
    previous: Double? = null, delta: Double? = null): GaugeModel {
    val factKind = when (kind) { GaugeKind.HOUR -> FactKind.PER_HOUR; GaugeKind.MILE -> FactKind.PER_MILE; GaugeKind.ITEM -> FactKind.PER_ITEM }
    fun precision(value: Double) = if (kind == GaugeKind.HOUR) Formats.decimal(value, 0).toDouble() else Formats.cents(value).toDouble()
    val versus = if (rate == null || target == null) null else if (precision(rate) >= precision(target)) Versus.ABOVE else Versus.BELOW
    return GaugeMapper.map(Fact(factKind, rate, target, versus, previous), Fact(FactKind.MINUTES, denominator, delta = delta))
}
