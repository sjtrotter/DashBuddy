package cloud.trotter.log.gig.feature.bubble.hud

import org.junit.Assert.*
import org.junit.Test

import cloud.trotter.log.gig.domain.model.offer.AddOnStops
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.model.offer.newStores
import cloud.trotter.log.gig.domain.model.offer.ownedStoreKeys
import cloud.trotter.log.gig.domain.model.offer.storeKey
import cloud.trotter.log.gig.domain.state.TaskPhase

class TripLineMapperTest {
    private val o = OfferFixtures.offer()
    private val job = OfferFixtures.job()
    private fun icons(offer: ParsedOffer) = TripLineMapper.offer(offer).nodes.map { it.icon }
    private fun added(offer: ParsedOffer = OfferFixtures.addon()) = TripLineMapper.withAddOn(job, offer).nodes.filter { it.added }
    @Test fun `single order is pin store house`() {
        assertEquals(listOf(TrackIcon.NAV_PIN, TrackIcon.STORE, TrackIcon.HOUSE), icons(o))
    }
    @Test fun `two orders at one store draw one store and two houses`() {
        assertEquals(listOf(TrackIcon.NAV_PIN, TrackIcon.STORE, TrackIcon.HOUSE, TrackIcon.HOUSE), icons(o.copy(orders = o.orders + o.orders)))
        assertEquals(listOf(TrackIcon.NAV_PIN, TrackIcon.STORE, TrackIcon.HOUSE, TrackIcon.HOUSE),
            icons(o.copy(orders = listOf(OfferFixtures.order("Store"), OfferFixtures.order("STORE")))))
    }
    @Test fun `two stores draw both stores then the houses`() {
        assertEquals(listOf(TrackIcon.NAV_PIN, TrackIcon.STORE, TrackIcon.STORE, TrackIcon.HOUSE, TrackIcon.HOUSE),
            icons(o.copy(orders = o.orders + OfferFixtures.order("Other"))))
    }
    @Test fun `blank store names count as one pickup`() {
        assertEquals(listOf(TrackIcon.NAV_PIN, TrackIcon.STORE, TrackIcon.HOUSE),
            icons(o.copy(orders = listOf(OfferFixtures.order(" ")))))
    }
    @Test fun `appended stops are marked added after the job`() {
        val nodes = TripLineMapper.withAddOn(job, OfferFixtures.addon()).nodes
        assertTrue(nodes.take(3).none { it.added })
        assertTrue(nodes.drop(3).all { it.added })
        assertEquals(listOf(TrackIcon.STORE, TrackIcon.HOUSE), nodes.drop(3).map { it.icon })
    }
    @Test fun `same store add on appends only houses`() {
        val addon = OfferFixtures.addon().copy(orders = o.orders + o.orders)
        assertTrue(added(addon).all { it.icon == TrackIcon.HOUSE })
    }
    @Test fun `added drops are stop count minus new stores`() {
        assertEquals(2, added(OfferFixtures.addon().copy(stopCount = 3)).count { it.icon == TrackIcon.HOUSE })
    }
    @Test fun `added nodes match the state machine's new stores and drop count`() {
        val owned = job.copy(tasks = job.tasks.map { if (it.phase == TaskPhase.PICKUP)
            it.copy(expectedStoreHint = "Old", storeName = "Old") else it })
        val offer = OfferFixtures.addon().copy(stopCount = 3,
            orders = listOf(OfferFixtures.order(" Old "), OfferFixtures.order("New", 3)))
        // appendAddOn prepares LISTED hints with distinctStoreHints: trim, remove blanks, dedupe.
        val hints = offer.orders.map { it.storeName.trim() }.filter { it.isNotEmpty() }.distinctBy(::storeKey)
        val stateStores = newStores(ownedStoreKeys(owned.tasks), hints)
        val stateDrops = AddOnStops.addedDropoffs(offer.stopCount, stateStores.size)
        assertEquals(listOf("New"), stateStores)
        assertEquals(2, stateDrops)
        assertEquals(stateStores, AddOnStops.newStores(ownedStoreKeys(owned.tasks), offer))
        val nodes = TripLineMapper.withAddOn(owned, offer).nodes.filter { it.added }
        assertEquals(1, nodes.count { it.icon == TrackIcon.STORE })
        assertEquals(2, nodes.count { it.icon == TrackIcon.HOUSE })
        assertEquals(stateStores.size, nodes.count { it.icon == TrackIcon.STORE })
        assertEquals(stateDrops, nodes.count { it.icon == TrackIcon.HOUSE })
        assertEquals(listOf(offer.orders[1]), CombinedOffer.newOrders(owned, offer))
        assertEquals(15, CombinedOffer.build(owned, offer)!!.itemCount)
    }
    @Test fun `unparsed stop count adds one house`() {
        assertEquals(1, added(OfferFixtures.addon().copy(stopCount = null)).count { it.icon == TrackIcon.HOUSE })
    }
    @Test fun `out of range stop count adds one house`() {
        listOf(0, 9, 999).forEach { stops ->
            assertEquals(1, added(OfferFixtures.addon().copy(stopCount = stops)).count { it.icon == TrackIcon.HOUSE })
        }
    }
    @Test fun `job tracked from its first dropoff still draws one store`() {
        val onlyDrop = job.copy(tasks = job.tasks.filter { it.phase == TaskPhase.DROPOFF })
        val nodes = TripLineMapper.withAddOn(onlyDrop, OfferFixtures.addon()).nodes
        assertEquals(TrackIcon.STORE, nodes[1].icon)
        assertFalse(nodes[1].added)
        assertEquals(1, nodes.count { it.added && it.icon == TrackIcon.HOUSE })
    }
    @Test fun `trip caps at nine nodes with the remaining count`() {
        val nodes = TripLineMapper.offer(o.copy(orders = List(10) { OfferFixtures.order("Store $it") })).nodes
        assertEquals(9, nodes.size)
        assertEquals(13, nodes.last().overflow)
    }
    @Test fun `nine nodes need no overflow node`() {
        val nodes = TripLineMapper.offer(o.copy(orders = List(4) { OfferFixtures.order("Store $it") })).nodes
        assertEquals(9, nodes.size)
        assertTrue(nodes.all { it.overflow == null })
    }
    @Test fun `combined overflow keeps the added stops marker`() {
        val nodes = TripLineMapper.withAddOn(job, OfferFixtures.addon().copy(stopCount = 8)).nodes
        assertEquals(9, nodes.size)
        assertEquals(3, nodes.last().overflow)
        assertTrue(nodes.last().added)
    }
    @Test fun `added stores use the job's resolved store names`() {
        val owned = job.copy(acceptedOffers = listOf(OfferFixtures.accept().copy(storeHints = listOf("Chick-fil-A"))),
            tasks = job.tasks.map { if (it.phase == TaskPhase.PICKUP)
            it.copy(expectedStoreHint = "Chick-fil-A", storeName = "Chick Fil A") else it })
        val offer = OfferFixtures.addon().copy(stopCount = 3,
            orders = listOf(OfferFixtures.order("chick fil a"), OfferFixtures.order("New", 3)))
        val nodes = TripLineMapper.withAddOn(owned, offer).nodes.filter { it.added }
        assertEquals(1, nodes.count { it.icon == TrackIcon.STORE })
        assertEquals(2, nodes.count { it.icon == TrackIcon.HOUSE })
        assertEquals(listOf("New"), CombinedOffer.newOrders(owned, offer).map { it.storeName })
    }
    @Test fun `padded owned store keeps the historical added stop counts`() {
        val owned = job.copy(tasks = job.tasks.map { if (it.phase == TaskPhase.PICKUP)
            it.copy(expectedStoreHint = " Old ", storeName = " Old ") else it })
        val offer = OfferFixtures.addon().copy(stopCount = 3,
            orders = listOf(OfferFixtures.order("Old"), OfferFixtures.order("New", 3)))
        val nodes = TripLineMapper.withAddOn(owned, offer).nodes.filter { it.added }
        assertEquals(2, nodes.count { it.icon == TrackIcon.STORE })
        assertEquals(1, nodes.count { it.icon == TrackIcon.HOUSE })
    }

}
