package cloud.trotter.dashbuddy.domain.model.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrderTypeTest {

    // -------------------------------------------------------------------------
    // fromTypeName
    // -------------------------------------------------------------------------

    @Test
    fun `fromTypeName - exact match returns correct type`() {
        assertEquals(OrderType.PICKUP, OrderType.fromTypeName("Pickup"))
        assertEquals(OrderType.RESTAURANT_PICKUP, OrderType.fromTypeName("Restaurant Pickup"))
        assertEquals(OrderType.SHOP_FOR_ITEMS, OrderType.fromTypeName("Shop for items"))
        assertEquals(OrderType.RETAIL_PICKUP, OrderType.fromTypeName("Retail pickup"))
    }

    @Test
    fun `fromTypeName - match is case insensitive`() {
        assertEquals(OrderType.PICKUP, OrderType.fromTypeName("pickup"))
        assertEquals(OrderType.PICKUP, OrderType.fromTypeName("PICKUP"))
        assertEquals(OrderType.SHOP_FOR_ITEMS, OrderType.fromTypeName("SHOP FOR ITEMS"))
    }

    @Test
    fun `fromTypeName - unknown text returns null`() {
        assertNull(OrderType.fromTypeName("Unknown"))
        assertNull(OrderType.fromTypeName(""))
        assertNull(OrderType.fromTypeName("Deliver"))
    }

    // -------------------------------------------------------------------------
    // orderTypeCount
    // -------------------------------------------------------------------------

    @Test
    fun `orderTypeCount - counts matching type names in list`() {
        val texts = listOf("Pickup", "Shop for items", "3.2 mi", "\$7.50")
        assertEquals(2, OrderType.orderTypeCount(texts))
    }

    @Test
    fun `orderTypeCount - empty list returns 0`() {
        assertEquals(0, OrderType.orderTypeCount(emptyList()))
    }

    @Test
    fun `orderTypeCount - no matches returns 0`() {
        assertEquals(0, OrderType.orderTypeCount(listOf("Accept", "Decline", "3.2 mi")))
    }

    @Test
    fun `orderTypeCount - multiple of same type counts each occurrence`() {
        val texts = listOf("Pickup", "Pickup", "Pickup")
        assertEquals(3, OrderType.orderTypeCount(texts))
    }

    @Test
    fun `orderTypeCount - matching is case insensitive`() {
        val texts = listOf("pickup", "SHOP FOR ITEMS")
        assertEquals(2, OrderType.orderTypeCount(texts))
    }

    // -------------------------------------------------------------------------
    // isShoppingOrder flag
    // -------------------------------------------------------------------------

    @Test
    fun `isShoppingOrder - SHOP_FOR_ITEMS is a shopping order`() {
        assert(OrderType.SHOP_FOR_ITEMS.isShoppingOrder)
    }

    @Test
    fun `isShoppingOrder - PICKUP is not a shopping order`() {
        assert(!OrderType.PICKUP.isShoppingOrder)
    }

    @Test
    fun `isShoppingOrder - RESTAURANT_PICKUP is not a shopping order`() {
        assert(!OrderType.RESTAURANT_PICKUP.isShoppingOrder)
    }

    @Test
    fun `isShoppingOrder - RETAIL_PICKUP is not a shopping order`() {
        assert(!OrderType.RETAIL_PICKUP.isShoppingOrder)
    }
}
