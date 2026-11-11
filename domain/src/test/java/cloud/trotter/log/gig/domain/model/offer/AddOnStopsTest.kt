package cloud.trotter.log.gig.domain.model.offer

import cloud.trotter.log.gig.domain.model.order.OrderType
import cloud.trotter.log.gig.domain.model.order.ParsedOrder
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class AddOnStopsTest {
    @Test fun `stop count minus new stores`() { assertEquals(2, AddOnStops.addedDropoffs(3, 1)) }
    @Test fun `floored at one`() { assertEquals(1, AddOnStops.addedDropoffs(2, 2)) }
    @Test fun `null is one`() { assertEquals(1, AddOnStops.addedDropoffs(null, 1)) }
    @Test fun `above the maximum is one`() {
        assertEquals(1, AddOnStops.addedDropoffs(AddOnStops.MAX_ADDON_STOPS + 1, 1))
    }
    // #1119 S2 (OQ9): the store key is the chain key — trimmed, lowercased, location qualifiers stripped.
    @Test fun `the store key is the chain key`() {
        assertEquals("old", storeKey(" OLD "))
        val owned = ownedStoreKeys(listOf(Task(
            "pickup", "job", TaskPhase.PICKUP,
            expectedStoreHint = " OLD ", storeName = " Old ", startedAt = 0)))
        assertEquals(listOf("old"), owned)
        assertEquals(listOf("New"), newStores(owned, listOf("Old", "New")))
        assertEquals(listOf(" New "), newStores(emptyList(), listOf(" New ", " NEW ", "New")))
    }
    @Test fun `qualifier variants are one store`() {
        assertEquals(storeKey("Store A (Loop 410)"), storeKey("STORE A #55"))
        val owned = ownedStoreKeys(listOf(Task(
            "pickup", "job", TaskPhase.PICKUP,
            expectedStoreHint = "Store A (Loop 410)", storeName = "Store A #55", startedAt = 0)))
        assertEquals(listOf("store a"), owned)
        assertEquals(listOf("Store C"), newStores(owned, listOf("Store A - Northside", "Store C")))
    }
    @Test fun `new stores key on lowercased names`() {
        val owned = ownedStoreKeys(listOf(Task(
            "pickup", "job", TaskPhase.PICKUP,
            expectedStoreHint = "Chick-fil-A", storeName = "Chick Fil A", startedAt = 0)))
        val offer = ParsedOffer("addon", orders = listOf("chick fil a", "CHICK-FIL-A", "New", "NEW", "")
            .mapIndexed { i, store -> ParsedOrder(
                i, OrderType.PICKUP, store, 1, false, emptySet()) })
        assertEquals(listOf("New"), newStores(owned, offer))
        // Owned keys are keys (ownedStoreKeys), never raw names.
        assertEquals(listOf("New"), newStores(listOf("chick fil a", "chick-fil-a"), offer))
    }
    @Test fun `offer hints are trimmed while owned keys keep their padding`() {
        val offer = ParsedOffer("addon", orders = listOf(" Old ", " New ", "NEW", " ")
            .mapIndexed { i, store -> ParsedOrder(i, OrderType.PICKUP, store, 1, false, emptySet()) })
        assertEquals(listOf("New"), AddOnStops.newStores(listOf("old"), offer))
        assertEquals(listOf("Old", "New"), AddOnStops.newStores(listOf(" old "), offer))
        assertEquals(AddOnStops.newStores(listOf("old"), offer), newStores(listOf("old"), offer))
    }
}
