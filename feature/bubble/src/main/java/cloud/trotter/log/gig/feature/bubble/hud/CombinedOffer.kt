package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.model.offer.AddOnStops
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.model.offer.ownedStoreKeys
import cloud.trotter.log.gig.domain.model.offer.storeKey
import cloud.trotter.log.gig.domain.model.order.CountUnit
import cloud.trotter.log.gig.domain.model.order.OrderType
import cloud.trotter.log.gig.domain.model.order.ParsedOrder
import cloud.trotter.log.gig.domain.state.Job

/** Accept-time economics, independent of arrival corrections and today's economy. */
data class JobQuote(val pay: Double, val net: Double, val minutes: Double, val miles: Double,
    val items: Int, val orders: List<ParsedOrder>, val units: Boolean) {
    /** Display-only evaluator input, never an action or persistence identity. */
    fun beforeOffer(hash: String) = ParsedOffer(offerHash = "before:" + hash,
        payAmount = pay, distanceMiles = miles, orders = orders.mapIndexed { i, o -> o.copy(orderIndex = i) },
        itemCount = items, itemCountIsUnits = units,
        quoteBasis = OfferQuoteBasis.TOTAL, offerScope = OfferScope.STANDALONE)
}

object CombinedOffer {
    fun quote(job: Job): JobQuote? {
        val accepts = job.acceptedOffers.takeIf { it.isNotEmpty() } ?: return null
        if (accepts.any { it.quoteBasis != OfferQuoteBasis.TOTAL || it.payAmount == null ||
                it.netPay == null || it.estMinutes == null || it.distanceMiles == null }) return null
        val orders = accepts.flatMap { accept ->
            val hints = accept.storeHints
            val shops = if (accept.isShop) (hints.size - accept.nonShopLegs).coerceAtLeast(0) else 0
            hints.mapIndexed { index, store ->
                ParsedOrder(index, if (index < shops) OrderType.SHOP_FOR_ITEMS else OrderType.PICKUP,
                    store, if (index == 0) accept.quotedItemCount ?: 1 else 0, false, emptySet(),
                    if (accept.offerUnitCount != null) CountUnit.UNITS else CountUnit.ITEMS)
            }
        }
        return JobQuote(job.offerPayTotal ?: return null, job.blendedNetPay ?: return null,
            job.blendedEstMinutes ?: return null, job.blendedDistanceMiles ?: return null,
            accepts.filter { it.isShop }.sumOf { it.quotedItemCount ?: 0 }, orders,
            accepts.all { it.offerUnitCount != null })
    }

    /**
     * Listed orders at stores the state machine calls new, in listing order.
     * A second order at an owned store is not counted as new, matching the state machine:
     * #1304 mints dropoffs by stop count, never per listed row.
     */
    internal fun newOrders(job: Job, offer: ParsedOffer): List<ParsedOrder> {
        val newStores = AddOnStops.newStores(ownedStoreKeys(job.tasks), offer).map(::storeKey).toSet()
        return offer.orders.filter { storeKey(it.storeName.trim()) in newStores }
    }

    /** Temporary evaluator input only. Never pass this synthetic identity to an action or writer. */
    fun build(job: Job, offer: ParsedOffer): ParsedOffer? {
        val before = quote(job) ?: return null
        val pay = offer.payAmount ?: return null
        val miles = offer.distanceMiles?.takeIf { it > 0 } ?: return null
        val added = newOrders(job, offer)
        return ParsedOffer(offerHash = "combined:" + offer.offerHash,
            payAmount = before.pay + pay, distanceMiles = before.miles + miles,
            orders = (before.orders + added).mapIndexed { i, order -> order.copy(orderIndex = i) },
            // A plain pickup carries a default count of one, which is not shopping (the parser never counts it).
            itemCount = before.items + added.filter { it.orderType == OrderType.SHOP_FOR_ITEMS }.sumOf { it.itemCount },
            itemCountIsUnits = before.units && offer.itemCountIsUnits,
            quoteBasis = OfferQuoteBasis.TOTAL, offerScope = OfferScope.STANDALONE)
    }
}
