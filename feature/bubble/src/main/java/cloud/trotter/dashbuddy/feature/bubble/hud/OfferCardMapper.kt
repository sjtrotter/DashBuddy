package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.evaluation.EvaluationConfig
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.offer.OfferQuoteBasis
import cloud.trotter.dashbuddy.domain.model.offer.OfferScope
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.offer.OfferBadge
import cloud.trotter.dashbuddy.domain.model.order.OrderBadge
import cloud.trotter.dashbuddy.domain.model.order.CountUnit
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.PendingOffer

object OfferCardMapper {
    fun map(p: PendingOffer, job: Job?, completed: List<FlowCardSnapshot>, config: EvaluationConfig?,
        evaluate: (ParsedOffer, EvaluationConfig) -> OfferEvaluation = OfferEvaluator()::evaluate): OfferCardModel {
        val o = p.offerFields.parsedOffer
        val head = OfferHead(o.displayStores.firstOrNull()?.takeIf { it.isNotBlank() }, o.payAmount,
            p.presentedAt, p.countdownExpiresAt)
        val badges = FlowCardSnapshot.Offer.badgesOf(o).map { name ->
            when (name) {
                FlowCardSnapshot.Offer.SHOP_BADGE -> OfferBadgeModel.Shop
                else -> OfferBadge.entries.firstOrNull { it.name == name }?.let(OfferBadgeModel::Platform)
                    ?: OfferBadgeModel.Order(OrderBadge.valueOf(name))
            }
        } + if (o.effectiveScope == OfferScope.ADD_ON) listOf(OfferBadgeModel.AddOn) else emptyList()
        if (o.quoteBasis == OfferQuoteBasis.TOTAL) {
            val e = p.evaluation
            val scored = e?.hasDistanceMetrics == true && o.payAmount != null
            return OfferCardModel.Standard(head, badges, TripLineMapper.offer(o),
                RingModel(e?.score?.toInt()?.takeIf { scored }),
                PayBarMapper.map(o.payAmount, e?.netPayAmount, e?.hasDistanceMetrics == true, e != null),
                gauges(o, e?.takeIf { scored }, OfferTargets.from(config, p.platform, o.isShop)))
        }
        val before = job?.let(CombinedOffer::quote)
        val summed = job?.let { CombinedOffer.build(it, o) }
        if (job != null && before != null && summed != null && config != null &&
            before.units == o.itemCountIsUnits && CombinedOffer.newOrders(job, o).all {
                it.countUnit == if (before.units) CountUnit.UNITS else CountUnit.ITEMS
            }) {
            val resolvedConfig = config.forPlatform(p.platform)
            val previousOffer = before.beforeOffer(o.offerHash)
            val previous = evaluate(previousOffer, resolvedConfig)
            val e = evaluate(summed, resolvedConfig)
            if (e.hasDistanceMetrics && previous.hasDistanceMetrics) {
                val was = job.acceptedOffers.singleOrNull()?.offerHash?.let { hash ->
                    completed.filterIsInstance<FlowCardSnapshot.Offer>().lastOrNull {
                        it.offerHash == hash && it.outcome == AppEventType.OFFER_ACCEPTED
                    }?.evaluationScore?.toInt()
                }
                return OfferCardModel.Combined(head.copy(pay = summed.payAmount, addedPay = o.payAmount),
                    badges, TripLineMapper.withAddOn(job, o), RingModel(e.score.toInt(), was),
                    PayBarMapper.map(summed.payAmount, e.netPayAmount, true, true),
                    gauges(summed, e, OfferTargets.from(config, p.platform, summed.isShop), previousOffer, previous))
            }
        }
        return OfferCardModel.AddOnRaw(head, badges, job?.let { TripLineMapper.withAddOn(it, o) }
            ?: TripLineMapper.offer(o), DeltaTiles(o.timeToCompleteMinutes, o.distanceMiles, o.stopCount, o.itemCount))
    }

    private fun gauges(o: ParsedOffer, e: OfferEvaluation?, targets: Targets,
        beforeOffer: ParsedOffer? = null, before: OfferEvaluation? = null): List<GaugeModel> {
        val items = o.itemCount.takeIf { it > 0 }?.toDouble()
        val previousItems = beforeOffer?.takeIf { it.isShop && it.itemCount > 0 }?.itemCount
        return listOfNotNull(
            GaugeMapper.map(GaugeKind.HOUR, e?.estimatedTimeMinutes, e?.dollarsPerHour, targets.perHour,
                before?.dollarsPerHour, before?.let { e?.estimatedTimeMinutes?.minus(it.estimatedTimeMinutes) }),
            GaugeMapper.map(GaugeKind.MILE, e?.distanceMiles, e?.dollarsPerMile, targets.perMile,
                before?.dollarsPerMile, before?.let { e?.distanceMiles?.minus(it.distanceMiles) }),
            if (o.isShop) GaugeMapper.map(GaugeKind.ITEM, items, items?.let { e?.netPayAmount?.div(it) },
                targets.perItem, previousItems?.let { before?.netPayAmount?.div(it) },
                beforeOffer?.let { items?.minus(it.itemCount) }) else null)
    }
}
