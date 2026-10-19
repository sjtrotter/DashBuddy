package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.model.offer.OfferQuoteBasis
import cloud.trotter.dashbuddy.domain.model.offer.OfferScope
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.CountUnit
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.state.Job
import java.util.Locale

/** Accept-time economics, independent of arrival corrections and today's economy. */
data class JobQuote(val pay: Double, val net: Double, val minutes: Double, val miles: Double,
    val items: Int, val orders: List<ParsedOrder>, val units: Boolean)

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
            accepts.sumOf { it.quotedItemCount ?: 0 }, orders, accepts.all { it.offerUnitCount != null })
    }

    /** Whole-route listings include the owned order. Remove its hints as a multiset, retaining duplicates. */
    internal fun newOrders(job: Job, offer: ParsedOffer): List<ParsedOrder> {
        val owned = job.acceptedOffers.flatMap { it.storeHints }.map { key(it) }.toMutableList()
        return offer.orders.filter { order ->
            val at = owned.indexOf(key(order.storeName))
            if (at < 0) true else { owned.removeAt(at); false }
        }
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
            itemCount = before.items + added.sumOf { it.itemCount },
            itemCountIsUnits = before.units && offer.itemCountIsUnits,
            quoteBasis = OfferQuoteBasis.TOTAL, offerScope = OfferScope.STANDALONE)
    }

    private fun key(store: String) = store.trim().lowercase(Locale.ROOT)
}
