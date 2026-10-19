package cloud.trotter.dashbuddy.feature.bubble.hud

import org.junit.Assert.*
import org.junit.Test

import cloud.trotter.dashbuddy.domain.model.offer.*
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.state.TaskPhase

class CombinedOfferTest {
    private val job = OfferFixtures.job()
    private val addon = OfferFixtures.addon()
    @Test fun `quote sums every accepted offer`() {
        val a = OfferFixtures.accept()
        val q = CombinedOffer.quote(job.copy(acceptedOffers = listOf(a, a)))!!
        assertEquals(23.5, q.pay, 0.0)
        assertEquals(16.58, q.net, 0.0)
        assertEquals(62.0, q.minutes, 0.0)
        assertEquals(12.8, q.miles, 0.0)
        assertEquals(24, q.items)
    }
    @Test fun `quote is null with an unscored accept`() {
        val a = OfferFixtures.accept()
        listOf(a.copy(netPay = null), a.copy(estMinutes = null), a.copy(distanceMiles = null), a.copy(payAmount = null)).forEach {
            assertNull(CombinedOffer.quote(job.copy(acceptedOffers = listOf(a, it))))
        }
    }
    @Test fun `quote is null once the job holds an incremental accept`() {
        assertNull(CombinedOffer.quote(job.copy(acceptedOffers = listOf(OfferFixtures.accept().copy(quoteBasis = OfferQuoteBasis.INCREMENTAL)))))
    }
    @Test fun `combined offer sums pay distance and items`() {
        val combined = CombinedOffer.build(job, addon)!!
        assertEquals(18.75, combined.payAmount!!, 0.0)
        assertEquals(8.5, combined.distanceMiles!!, 0.0)
        assertEquals(15, combined.itemCount)
        assertFalse(combined.itemCountIsUnits)
    }
    @Test fun `combined offer rebuilds shop and non shop legs from the accept`() {
        val a = OfferFixtures.accept().copy(storeHints = listOf("Shop", "Pickup"), nonShopLegs = 1)
        val combined = CombinedOffer.build(job.copy(acceptedOffers = listOf(a)), addon)!!
        assertEquals(listOf(OrderType.SHOP_FOR_ITEMS, OrderType.PICKUP), combined.orders.take(2).map { it.orderType })
    }
    @Test fun `combined offer appends only the add on's new orders`() {
        val combined = CombinedOffer.build(job, addon)!!
        assertEquals(listOf("Store", "New"), combined.orders.map { it.storeName })
        assertEquals(listOf(0, 1), combined.orders.map { it.orderIndex })
    }
    @Test fun `owned orders are subtracted case insensitively as a multiset`() {
        val a = OfferFixtures.accept().copy(storeHints = listOf("Store", "STORE"), quotedItemCount = 24)
        val repeated = addon.copy(orders = listOf(
            OfferFixtures.order(" store "), OfferFixtures.order("STORE"), OfferFixtures.order("Store", 3)))
        val combined = CombinedOffer.build(job.copy(acceptedOffers = listOf(a),
            tasks = job.tasks + job.tasks.first().copy(taskId = "pickup2")), repeated)!!
        assertEquals(3, combined.orders.size)
        assertEquals(27, combined.itemCount)
    }
    @Test fun `without owned pickups all add on orders are new`() {
        val a = OfferFixtures.accept().copy(storeHints = emptyList())
        val combined = CombinedOffer.build(job.copy(acceptedOffers = listOf(a),
            tasks = job.tasks.filter { it.phase != TaskPhase.PICKUP }), addon)!!
        assertEquals(listOf("Store", "New"), combined.orders.map { it.storeName })
        assertEquals(27, combined.itemCount)
    }
    @Test fun `a fully subtracted listing is a same store add on and keeps its orders as new`() {
        // The add-on's pay and miles are deltas, so a listing that only matches the owned store still adds a stop.
        val combined = CombinedOffer.build(job, addon.copy(orders = listOf(OfferFixtures.order("STORE", 3))))!!
        assertEquals(listOf("Store", "STORE"), combined.orders.map { it.storeName })
        assertEquals(15, combined.itemCount)
    }
    @Test fun `adding a plain pickup to a shop adds no items`() {
        val combined = CombinedOffer.build(job, addon.copy(orders = listOf(OfferFixtures.order("New", 1, shop = false))))!!
        assertEquals(2, combined.orders.size)
        assertEquals(12, combined.itemCount)
    }
    @Test fun `same store add on keeps one new order`() {
        val same = addon.copy(orders = listOf(OfferFixtures.order("STORE"), OfferFixtures.order("store", 3)))
        val combined = CombinedOffer.build(job, same)!!
        assertEquals(2, combined.orders.size)
        assertEquals(15, combined.itemCount)
    }
    @Test fun `combined offer is a total standalone quote`() {
        val combined = CombinedOffer.build(job, addon)!!
        assertEquals(OfferQuoteBasis.TOTAL, combined.quoteBasis)
        assertEquals(OfferScope.STANDALONE, combined.effectiveScope)
        assertEquals("combined:addon", combined.offerHash)
    }
    @Test fun `combined units require both sides to be units`() {
        val unitsJob = job.copy(acceptedOffers = listOf(OfferFixtures.accept().copy(offerUnitCount = 12)))
        assertTrue(CombinedOffer.build(unitsJob, addon.copy(itemCountIsUnits = true))!!.itemCountIsUnits)
        assertFalse(CombinedOffer.build(unitsJob, addon)!!.itemCountIsUnits)
    }
    @Test fun `before offer rebuilds the accepted quote as a total`() {
        val a = OfferFixtures.accept().copy(storeHints = listOf("Shop", "Pickup"), nonShopLegs = 1, offerUnitCount = 12)
        val before = CombinedOffer.quote(job.copy(acceptedOffers = listOf(a)))!!.beforeOffer(addon.offerHash)
        assertEquals("before:addon", before.offerHash)
        assertEquals(OfferQuoteBasis.TOTAL, before.quoteBasis)
        assertEquals(OfferScope.STANDALONE, before.effectiveScope)
        assertEquals(11.75, before.payAmount!!, 0.0)
        assertEquals(6.4, before.distanceMiles!!, 0.0)
        assertEquals(12, before.itemCount)
        assertTrue(before.itemCountIsUnits)
        assertEquals(listOf("Shop", "Pickup"), before.orders.map { it.storeName })
        assertEquals(listOf(0, 1), before.orders.map { it.orderIndex })
        assertEquals(listOf(OrderType.SHOP_FOR_ITEMS, OrderType.PICKUP), before.orders.map { it.orderType })
        assertNull(before.timeToCompleteMinutes)
    }
    @Test fun `new orders use the job's resolved store names`() {
        val owned = job.copy(acceptedOffers = listOf(OfferFixtures.accept().copy(storeHints = listOf("Chick-fil-A"))),
            tasks = job.tasks.map { if (it.phase == TaskPhase.PICKUP)
                it.copy(expectedStoreHint = "Chick-fil-A", storeName = "Chick Fil A") else it })
        val offer = addon.copy(orders = listOf(OfferFixtures.order(" chick fil a "), OfferFixtures.order("New", 3)))
        assertEquals(listOf("New"), CombinedOffer.newOrders(owned, offer).map { it.storeName })
        assertEquals(15, CombinedOffer.build(owned, offer)!!.itemCount)
    }

}
