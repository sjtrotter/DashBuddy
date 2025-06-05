package cloud.trotter.dashbuddy.data.offer

// Import your UtilityFunctions if they are directly called by tests or needed for setup.
// For now, OfferParser calls them internally.
// import cloud.trotter.dashbuddy.util.UtilityFunctions

import cloud.trotter.dashbuddy.data.order.OrderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import cloud.trotter.dashbuddy.data.order.OrderBadge as OrderSpecificBadge

class OfferParserTest {

    // --- Test Data ---
    private val simplePickupTexts = listOf(
        "Decline",
        "$10.00 Guaranteed (incl. tips)",
        "5.0 mi",
        "Deliver by 10:30 AM",
        "Pickup", "The Coffee House", // OrderType, StoreName
        "Customer dropoff",           // End of ordersRegion
        "Accept",
        "35"                          // Timer
    )

    private val shopAndPayWithBadgeTexts = listOf(
        "Decline",
        "$12.50 Guaranteed (incl. tips)",
        "2.1 mi",
        "Deliver by 11:00 AM",
        "Shop for items",
        "SuperMart",
        "(3 items)",
        "Red Card required", // OrderType, StoreName, Details...
        "Customer dropoff",
        "Items can be added before checkout", // Offer-level badge
        "Accept",
        "40"
    )

    private val stackedDifferentStoresTexts = listOf(
        "Decline",
        "$18.00+ Total will be higher",
        "8.2 mi",
        "Deliver by 1:45 PM",
        "Restaurant Pickup", "Pizza Place", "(1 item)", "Pizza bag required", // Order 1
        "Pickup", "Drink Stop", "(2 items)", // Order 2
        "Customer dropoff", // End of ordersRegion
        "Both orders go to the same customer", // Offer-level badge
        "Accept",
        "28"
    )

    private val stackedSameStoreMultiOrderPatternTexts = listOf(
        "Decline",
        "$9.00 Guaranteed (incl. tips)",
        "3.5 mi",
        "Deliver by 2:30 PM",
        "Retail pickup", "Corner Store", "(2 orders)", // OrderType, StoreName, (2 orders) pattern
        "Customer dropoff",
        "Customer dropoff", // OfferParser uses first one to delimit ordersRegion
        "All orders are from the same store",
        "Accept",
        "32"
    )

    private val shopWithStackedItemsAndOrdersPattern = listOf(
        "Decline",
        "$20.00 Guaranteed (incl. tips)",
        "4.0 mi",
        "Deliver by 3:00 PM",
        "Shop for items", "MegaMart", "(2 orders, 7 items)", "Red Card required",
        "Customer dropoff",
        "Accept",
        "25"
    )

    private val offerWithNoItemsExplicitlyListedForPickup = listOf(
        "Decline",
        "$5.00 Guaranteed (incl. tips)",
        "1.5 mi",
        "Deliver by 4:00 PM",
        "Pickup", "Quick Eats",
        "Customer dropoff",
        "Accept",
        "38"
    )

    // --- Test Methods ---

    @Test
    fun `parseOffer with simple pickup returns correct ParsedOffer`() {
        val result = OfferParser.parseOffer(simplePickupTexts)

        assertNotNull("ParsedOffer should not be null", result)
        result?.let { // Safe call
            assertEquals(10.0, it.payAmount!!, 0.001)
            assertEquals("$10.00 Guaranteed (incl. tips)", it.payTextRaw)
            assertEquals(5.0, it.distanceMiles!!, 0.001)
            assertEquals("5.0 mi", it.distanceTextRaw)
            assertEquals("10:30 AM", it.dueByTimeText) // The time string part
            assertNotNull(it.dueByTimeMillis)
            assertTrue("Offer badges should be empty", it.badges.isEmpty())
            assertEquals(35, it.initialCountdownSeconds)
            assertFalse("OfferHash should not be blank", it.offerHash.isBlank())
            assertEquals(1, it.itemCount) // From orders list sum or heuristic

            assertEquals("Expected 1 order", 1, it.orders.size)
            val order = it.orders[0]
            assertEquals(OrderType.PICKUP.typeName, order.orderType)
            assertEquals("The Coffee House", order.storeName)
            assertEquals(1, order.itemCount) // Default for non-shop pickup
            assertFalse(order.isItemCountEstimated)
            assertTrue("Order-specific badges should be empty", order.badges.isEmpty())
            assertEquals(0, order.orderIndex)
        }
    }

    @Test
    fun `parseOffer with shop and pay and badges returns correct ParsedOffer`() {
        val result = OfferParser.parseOffer(shopAndPayWithBadgeTexts)

        assertNotNull(result)
        result?.let {
            assertEquals(12.50, it.payAmount!!, 0.001)
            assertEquals(2.1, it.distanceMiles!!, 0.001)
            assertEquals("11:00 AM", it.dueByTimeText)
            assertEquals(40, it.initialCountdownSeconds)
            assertTrue(
                "Should contain ITEMS_CAN_BE_ADDED badge",
                it.badges.contains(OfferBadge.ITEMS_CAN_BE_ADDED)
            )
            assertEquals(3, it.itemCount) // From (3 items)

            assertEquals("Expected 1 order", 1, it.orders.size)
            val order = it.orders[0]
            assertEquals(OrderType.SHOP_FOR_ITEMS.typeName, order.orderType)
            assertEquals("SuperMart", order.storeName)
            assertEquals(3, order.itemCount)
            assertFalse(order.isItemCountEstimated)
            assertTrue(
                "Should contain RED_CARD badge",
                order.badges.contains(OrderSpecificBadge.RED_CARD)
            )
            assertEquals(0, order.orderIndex)
        }
    }

    @Test
    fun `parseOffer with stacked different stores returns correct ParsedOffer`() {
        val result = OfferParser.parseOffer(stackedDifferentStoresTexts)

        assertNotNull(result)
        result?.let {
            assertEquals(18.00, it.payAmount!!, 0.001)
            assertTrue(
                "Should contain BOTH_ORDERS_SAME_CUSTOMER badge",
                it.badges.contains(OfferBadge.BOTH_ORDERS_SAME_CUSTOMER)
            )
            assertEquals(28, it.initialCountdownSeconds)
            // Item count: (1 item for Pizza Place) + (2 items for Drink Stop) = 3
            assertEquals(3, it.itemCount)


            assertEquals("Expected 2 orders", 2, it.orders.size)

            val order1 = it.orders.find { o -> o.storeName == "Pizza Place" }
            assertNotNull(order1)
            assertEquals(OrderType.RESTAURANT_PICKUP.typeName, order1?.orderType)
            assertEquals(1, order1?.itemCount)
            assertTrue(order1?.badges?.contains(OrderSpecificBadge.PIZZA_BAG) == true)
            assertEquals(0, order1?.orderIndex)


            val order2 = it.orders.find { o -> o.storeName == "Drink Stop" }
            assertNotNull(order2)
            assertEquals(OrderType.PICKUP.typeName, order2?.orderType)
            assertEquals(2, order2?.itemCount)
            assertTrue(order2?.badges?.isEmpty() == true)
            assertEquals(1, order2?.orderIndex)
        }
    }

    @Test
    fun `parseOffer with stacked same store multi order pattern returns correct ParsedOffer`() {
        val result = OfferParser.parseOffer(stackedSameStoreMultiOrderPatternTexts)

        assertNotNull(result)
        result?.let {
            assertEquals(9.00, it.payAmount!!, 0.001)
            assertTrue(
                "Should contain ALL_ORDERS_SAME_STORE badge",
                it.badges.contains(OfferBadge.ALL_ORDERS_SAME_STORE)
            )
            // Heuristic numberOfOrders in OfferParser will be max(1 type, 2 dropoffs) = 2.
            // OrderParser receives ordersRegionTexts = ["Retail pickup", "Corner Store", "(2 orders)"]
            // OrderParser's MULTI_ORDER_ONLY_REGEX matches "(2 orders)", creates 2 ParsedOrder objects.
            // Reconciliation in OrderParser: ordersParsed (2) == totalOrders (2 from heuristic), no duplication.
            assertEquals("Expected 2 orders", 2, it.orders.size)
            assertEquals(2, it.itemCount) // 1 item per order from (2 orders) pattern

            it.orders.forEachIndexed { index, order ->
                assertEquals(OrderType.RETAIL_PICKUP.typeName, order.orderType)
                assertEquals("Corner Store", order.storeName)
                assertEquals(1, order.itemCount) // From (2 orders) pattern
                assertFalse(order.isItemCountEstimated)
                assertTrue(order.badges.isEmpty())
                assertEquals(index, order.orderIndex)
            }
        }
    }

    @Test
    fun `parseOffer with shop and pay stacked items and orders pattern`() {
        val result = OfferParser.parseOffer(shopWithStackedItemsAndOrdersPattern)
        assertNotNull(result)
        result?.let {
            assertEquals(20.0, it.payAmount!!, 0.001)
            // OrderParser gets ordersRegionTexts = ["Shop for items", "MegaMart", "(2 orders, 7 items)", "Red Card required"]
            // STACKED_ORDER_ITEM_COUNT_REGEX matches, numOrdersFromPattern = 2, itemsInThisOrderLeg = round(7/2) = 4.
            // Two ParsedOrder objects created.
            assertEquals("Expected 2 orders", 2, result.orders.size)
            assertEquals(8, result.itemCount) // 4 items per order * 2 orders

            result.orders.forEachIndexed { index, order ->
                assertEquals(OrderType.SHOP_FOR_ITEMS.typeName, order.orderType)
                assertEquals("MegaMart", order.storeName)
                assertEquals(4, order.itemCount)
                assertTrue(order.isItemCountEstimated)
                assertTrue(order.badges.contains(OrderSpecificBadge.RED_CARD))
                assertEquals(index, order.orderIndex)
            }
        }
    }

    @Test
    fun `parseOffer with pickup and no explicit item count`() {
        val result = OfferParser.parseOffer(offerWithNoItemsExplicitlyListedForPickup)
        assertNotNull(result)
        result?.let {
            assertEquals(5.0, it.payAmount!!, 0.001)
            assertEquals(1, result.orders.size)
            val order = result.orders[0]
            assertEquals("Quick Eats", order.storeName)
            assertEquals(OrderType.PICKUP.typeName, order.orderType)
            assertEquals(1, order.itemCount) // Default for non-shop
            assertFalse(order.isItemCountEstimated)
            assertEquals(1, result.itemCount)
        }
    }

    @Test
    fun `parseOffer with empty screenTexts returns null`() {
        val result = OfferParser.parseOffer(emptyList())
        assertNull("Result should be null for empty input", result)
    }

    @Test
    fun `parseOffer missing payAmount and distanceMiles might return null`() {
        // Your OfferParser currently returns null if orders.isEmpty() AND (payAmount or distance is null)
        // This test assumes that if orders are also empty, it will be null.
        val screenTexts = listOf(
            "Deliver by 10:30 AM",
            "Pickup", "NoPay Cafe",
            "Customer dropoff",
            "Accept",
            "30"
        )
        val result = OfferParser.parseOffer(screenTexts)
        // Based on your current OfferParser: if orders.isEmpty() is true (which it will be here),
        // and payAmount and distanceMiles are null, it will return the ParsedOffer with nulls for those.
        // The final null check in your OfferParser is:
        // if (orders.isEmpty()) { Log.w(TAG, "!!! No orders were parsed!"); return null }
        // So this test should expect null.
        assertNull(
            "Result should be null if orders are not parsed (and pay/distance might be missing)",
            result
        )
    }

    @Test
    fun `parseOffer missing firstCustomerDropoff should still attempt parsing`() {
        // This tests the fallback logic in OfferParser if firstCustomerDropoffIndex is -1
        val screenTexts = listOf(
            "Decline",
            "$10.00 Guaranteed (incl. tips)",
            "5.0 mi",
            "Deliver by 10:30 AM",
            "Pickup", "The Coffee House",
            // NO "Customer dropoff" before Accept
            "Accept",
            "35"
        )
        val result = OfferParser.parseOffer(screenTexts)
        assertNotNull(
            "ParsedOffer should not be null even if CustomerDropoff is missing before Accept",
            result
        )
        result?.let {
            assertEquals(10.0, it.payAmount!!, 0.001)
            assertEquals(1, it.orders.size)
            assertEquals("The Coffee House", it.orders[0].storeName)
        }
    }


}
