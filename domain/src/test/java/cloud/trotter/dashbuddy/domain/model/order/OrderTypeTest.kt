package cloud.trotter.dashbuddy.domain.model.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recognition is data, not code (CLAUDE.md): the text-matching helpers
 * (`fromTypeName`/`orderTypeCount`/`allTypeNames`/`typeName`) were superseded by the JSON rule
 * engine and deleted, so their tests are gone too. `ParsedFieldsFactory` now resolves the order
 * type from parse output via the intrinsic [OrderType.valueOf]; an unrecognized string maps to
 * [OrderType.UNKNOWN]. What remains is the [OrderType.isShoppingOrder] flag.
 */
class OrderTypeTest {

    // -------------------------------------------------------------------------
    // isShoppingOrder flag
    // -------------------------------------------------------------------------

    @Test
    fun `isShoppingOrder - SHOP_FOR_ITEMS is a shopping order`() {
        assertTrue(OrderType.SHOP_FOR_ITEMS.isShoppingOrder)
    }

    @Test
    fun `isShoppingOrder - PICKUP is not a shopping order`() {
        assertFalse(OrderType.PICKUP.isShoppingOrder)
    }

    @Test
    fun `isShoppingOrder - UNKNOWN degrades to non-shopping`() {
        assertFalse(OrderType.UNKNOWN.isShoppingOrder)
    }

    // -------------------------------------------------------------------------
    // Serialized-key contract (ParsedFieldsFactory resolves via valueOf)
    // -------------------------------------------------------------------------

    @Test
    fun `valueOf round-trips the serialized name for every type`() {
        OrderType.entries.forEach { type ->
            assertEquals(type, OrderType.valueOf(type.name))
        }
    }

    @Test
    fun `the enum shape is exactly PICKUP, SHOP_FOR_ITEMS, UNKNOWN`() {
        assertEquals(
            listOf(OrderType.PICKUP, OrderType.SHOP_FOR_ITEMS, OrderType.UNKNOWN),
            OrderType.entries.toList(),
        )
    }
}
