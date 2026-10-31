package cloud.trotter.log.gig.domain.offer.story

import cloud.trotter.log.gig.domain.evaluation.*
import cloud.trotter.log.gig.domain.model.offer.*
import cloud.trotter.log.gig.domain.model.order.*
import cloud.trotter.log.gig.domain.state.*

object OfferFixtures {
    val config = EvaluationConfig(rules = listOf(
        ScoringRule.MetricRule("hour", metricType = MetricType.ACTIVE_HOURLY, targetValue = 18f),
        ScoringRule.MetricRule("mile", metricType = MetricType.DOLLAR_PER_MILE, targetValue = 1.5f)))
    fun order(store: String = "Store", items: Int = 12, shop: Boolean = true) = ParsedOrder(
        0, if (shop) OrderType.SHOP_FOR_ITEMS else OrderType.PICKUP, store, items, false, emptySet())
    fun offer() = ParsedOffer("real", payAmount = 11.75, distanceMiles = 6.4,
        itemCount = 12, orders = listOf(order()), initialCountdownSeconds = 30)
    fun evaluation(o: ParsedOffer = offer()) = OfferEvaluator().evaluate(o, config.forPlatform(Platform.DoorDash))
        .copy(score = 74.0, netPayAmount = 8.29, estimatedTimeMinutes = 31.0,
            dollarsPerHour = 16.0, dollarsPerMile = 1.30)
    fun pending(o: ParsedOffer = offer(), e: OfferEvaluation? = evaluation(o)) = PendingOffer(
        o.offerHash, ParsedFields.OfferFields(parsedOffer = o), 1_000, e,
        returnFlow = Flow.TaskPickupNavigation, sourceRuleId = "doordash.screen.offer", countdownExpiresAt = 31_000)
    fun accept() = AcceptedOfferEconomics("before", payAmount = 11.75, netPay = 8.29,
        estMinutes = 31.0, distanceMiles = 6.4, acceptedAt = 0,
        storeHints = listOf("Store"), isShop = true, quotedItemCount = 12)
    fun job() = Job("job", listOf("Store"), "before", listOf(accept()), listOf(
        Task("pickup", "job", TaskPhase.PICKUP, expectedStoreHint = "Store", startedAt = 0),
        Task("drop", "job", TaskPhase.DROPOFF, startedAt = 0)), startedAt = 0)
    fun addon() = offer().copy(offerHash = "addon", quoteBasis = OfferQuoteBasis.INCREMENTAL,
        offerScope = OfferScope.ADD_ON, payAmount = 7.0, distanceMiles = 2.1,
        timeToCompleteMinutes = 12, stopCount = 2, itemCount = 15,
        orders = listOf(order(), order("New", 3)))
}
