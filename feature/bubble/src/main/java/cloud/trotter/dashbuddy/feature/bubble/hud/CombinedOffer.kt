package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.model.offer.OfferQuoteBasis
import cloud.trotter.dashbuddy.domain.model.offer.OfferScope
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.offer.storeKey
import cloud.trotter.dashbuddy.domain.model.order.CountUnit
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.TaskPhase

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
     * Whole-route listings include owned orders. Each accepted hint owns one order, with its pickup's
     * resolved name as an alias; several orders can share one pickup without losing their multiplicity.
     * An add-on whose every listed order matches an owned store (a second order at the same store) still
     * ADDS a stop: its pay and miles are deltas, so the listing is taken whole rather than emptied.
     */
    internal fun newOrders(job: Job, offer: ParsedOffer): List<ParsedOrder> {
        val pickups = job.tasks.filter { it.phase == TaskPhase.PICKUP }
        val owned = job.acceptedOffers.flatMap { it.storeHints }.map { hint ->
            val key = storeKey(hint)
            setOf(key) + pickups.filter { it.expectedStoreHint?.let(::storeKey) == key }
                .mapNotNull { it.storeName?.let(::storeKey) }
        }.toMutableList()
        val remaining = offer.orders.filter { order ->
            val key = storeKey(order.storeName)
            val at = owned.indexOfFirst { key in it }
            if (at < 0) true else { owned.removeAt(at); false }
        }
        return if (remaining.isEmpty() && offer.orders.isNotEmpty()) offer.orders else remaining
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
