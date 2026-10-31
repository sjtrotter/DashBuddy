package cloud.trotter.log.gig.domain.offer.story

import cloud.trotter.log.gig.domain.evaluation.*
import cloud.trotter.log.gig.domain.model.offer.*
import cloud.trotter.log.gig.domain.state.*

/** Shared landed facts for independent text, voice and domain assertions. */
object StoryFixtures {
    val config = OfferFixtures.config
    val offer = OfferFixtures.offer().copy(payAmount = 24.50, distanceMiles = 12.9,
        orders = listOf(OfferFixtures.order("H-E-B")))
    val evaluation = OfferFixtures.evaluation(offer).copy(netPayAmount = 22.48,
        distanceMiles = 12.9, estimatedTimeMinutes = 45.0, dollarsPerHour = 22.0)
    fun total(platform: Platform = Platform.DoorDash, requote: Boolean = false): OfferStory =
        OfferStories.build(OfferFixtures.pending(offer, evaluation), null, config, platform, requote)
    fun distanceUnknown() = OfferStories.build(OfferFixtures.pending(offer.copy(payAmount = 12.5, distanceMiles = null),
        evaluation.copy(payAmount = 12.5, distanceMiles = 0.0)), null, config, Platform.DoorDash)
    fun payUnknown() = OfferStories.build(OfferFixtures.pending(offer.copy(payAmount = null), evaluation),
        null, config, Platform.DoorDash)
    val addOn = OfferFixtures.addon().copy(payAmount = 6.5, distanceMiles = 3.1, timeToCompleteMinutes = 8,
        orders = listOf(OfferFixtures.order("H-E-B"), OfferFixtures.order("New", 3)))
    val job = OfferFixtures.job().copy(acceptedOffers = listOf(OfferFixtures.accept().copy(
        payAmount = 28.0, netPay = 24.0, estMinutes = 50.0, distanceMiles = 12.1)))
    fun addOn(jobKnown: Boolean = true, config: EvaluationConfig = StoryFixtures.config): OfferStory =
        OfferStories.build(OfferFixtures.pending(addOn), if (jobKnown) job else null, config, Platform.Uber) { parsed, _ ->
            evaluation.copy(payAmount = parsed.payAmount!!, distanceMiles = parsed.distanceMiles!!,
                netPayAmount = if (parsed.offerHash.startsWith("combined:")) 30.1 else 24.0,
                estimatedTimeMinutes = if (parsed.offerHash.startsWith("combined:")) 58.0 else 50.0,
                dollarsPerHour = 31.0)
        }
    fun blocked() = total().copy(rules = listOf(RuleHit.BLOCKED_STORE))
    /** Every target an offer can cross, each its own enabled rule beside the fixture rates. */
    fun limitConfig(net: Float? = null, miles: Float? = null, items: Float? = null, enabled: Boolean = true) =
        config.copy(rules = config.rules + listOfNotNull(
            net?.let { ScoringRule.MetricRule("net", enabled, MetricType.PAYOUT, it) },
            miles?.let { ScoringRule.MetricRule("dist", enabled, MetricType.MAX_DISTANCE, it) },
            items?.let { ScoringRule.MetricRule("items", enabled, MetricType.ITEM_COUNT, it) }))
    /** The total story with its net below a $7.00 minimum. */
    fun netLimit() = total().copy(limits = listOf(LimitHit(LimitKind.NET, 7.0)))
    fun loss(amount: Double = -3.75) = total().copy(facts = total().facts.map {
        if (it.kind == FactKind.NET) it.copy(value = amount) else it
    })
}
