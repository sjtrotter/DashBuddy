package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.offer.story.*
import cloud.trotter.log.gig.domain.model.cards.FlowCardSnapshot
import cloud.trotter.log.gig.domain.model.offer.OfferBadge
import cloud.trotter.log.gig.domain.model.order.OrderBadge
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.PendingOffer

object OfferCardMapper {
    fun map(story: OfferStory, p: PendingOffer, job: Job?): OfferCardModel {
        val o = p.offerFields.parsedOffer
        val head = OfferHead(story.store, story.fact(FactKind.PAY)?.value, p.presentedAt, p.countdownExpiresAt,
            if (story.basis == StoryBasis.ADD_ON_JOB) story.added.firstOrNull { it.kind == FactKind.PAY }?.value else null)
        val badges = story.preferences().map { hit ->
            when (hit) {
                is PreferenceHit.Rule -> OfferBadgeModel.Rule(hit.hit)
                is PreferenceHit.Limit -> OfferBadgeModel.Limit(hit.hit)
            }
        } + FlowCardSnapshot.Offer.badgesOf(o).map { name ->
            when (name) {
                FlowCardSnapshot.Offer.SHOP_BADGE -> OfferBadgeModel.Shop
                else -> OfferBadge.entries.firstOrNull { it.name == name }?.let(OfferBadgeModel::Platform)
                    ?: OfferBadgeModel.Order(OrderBadge.valueOf(name))
            }
        } + if (story.addOn) listOf(OfferBadgeModel.AddOn) else emptyList()
        val trip = if (story.basis != StoryBasis.TOTAL && job != null) TripLineMapper.withAddOn(job, o)
            else TripLineMapper.offer(o)
        if (story.basis == StoryBasis.ADD_ON_DELTA) return OfferCardModel.AddOnRaw(head, badges, trip,
            DeltaTiles(story.fact(FactKind.MINUTES)?.value?.toLong(), story.fact(FactKind.MILES)?.value,
                story.fact(FactKind.STOPS)?.value?.toInt(), story.fact(FactKind.ITEMS)?.value?.toInt()))
        val net = story.fact(FactKind.NET)?.value
        val bar = PayBarMapper.map(head.pay, net, net != null, Missing.ESTIMATE !in story.missing)
        val gauges = listOfNotNull(
            story.fact(FactKind.PER_HOUR)?.let { GaugeMapper.map(it, story.fact(FactKind.MINUTES)) },
            story.fact(FactKind.PER_MILE)?.let { GaugeMapper.map(it, story.fact(FactKind.MILES)) },
            story.fact(FactKind.PER_ITEM)?.let { GaugeMapper.map(it, story.fact(FactKind.ITEMS)) })
        return if (story.basis == StoryBasis.ADD_ON_JOB) OfferCardModel.Combined(head, badges, trip, bar, gauges)
            else OfferCardModel.Standard(head, badges, trip, bar, gauges)
    }
}
