package cloud.trotter.dashbuddy.domain.model.offer

import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class AddOnStopsTest {
    @Test fun `stop count minus new stores`() { assertEquals(2, AddOnStops.addedDropoffs(3, 1)) }
    @Test fun `floored at one`() { assertEquals(1, AddOnStops.addedDropoffs(2, 2)) }
    @Test fun `null is one`() { assertEquals(1, AddOnStops.addedDropoffs(null, 1)) }
    @Test fun `above the maximum is one`() {
        assertEquals(1, AddOnStops.addedDropoffs(AddOnStops.MAX_ADDON_STOPS + 1, 1))
    }
    @Test fun `new stores key on trimmed lowercase names`() {
        val owned = ownedStoreKeys(listOf(Task(
            "pickup", "job", TaskPhase.PICKUP,
            expectedStoreHint = " Chick-fil-A ", storeName = "Chick Fil A", startedAt = 0)))
        val offer = ParsedOffer("addon", orders = listOf(" chick fil a ", "CHICK-FIL-A", " New ", "NEW", " ")
            .mapIndexed { i, store -> ParsedOrder(
                i, OrderType.PICKUP, store, 1, false, emptySet()) })
        assertEquals(listOf("New"), newStores(owned, offer))
        assertEquals(listOf("New"), newStores(listOf(" CHICK FIL A ", "CHICK-FIL-A"), offer))
    }
}
