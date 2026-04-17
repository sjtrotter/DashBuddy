package cloud.trotter.dashbuddy.domain.model.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderBadgeTest {

    @Test
    fun `RED_CARD detected by exact text`() {
        val badges = OrderBadge.findAllBadgesInOrderBlock(listOf("Red Card required"))
        assertTrue(OrderBadge.RED_CARD in badges)
    }

    @Test
    fun `RED_CARD match is case insensitive`() {
        val badges = OrderBadge.findAllBadgesInOrderBlock(listOf("red card required"))
        assertTrue(OrderBadge.RED_CARD in badges)
    }

    @Test
    fun `LARGE_ORDER detected`() {
        val badges = OrderBadge.findAllBadgesInOrderBlock(listOf("Large Order - Catering"))
        assertTrue(OrderBadge.LARGE_ORDER in badges)
    }

    @Test
    fun `PIZZA_BAG detected`() {
        val badges = OrderBadge.findAllBadgesInOrderBlock(listOf("Pizza bag required"))
        assertTrue(OrderBadge.PIZZA_BAG in badges)
    }

    @Test
    fun `ALCOHOL detected`() {
        val badges = OrderBadge.findAllBadgesInOrderBlock(listOf("Alcohol"))
        assertTrue(OrderBadge.ALCOHOL in badges)
    }

    @Test
    fun `multiple badges detected across order block`() {
        val orderTexts = listOf("Red Card required", "Pizza bag required")
        val badges = OrderBadge.findAllBadgesInOrderBlock(orderTexts)
        assertTrue(OrderBadge.RED_CARD in badges)
        assertTrue(OrderBadge.PIZZA_BAG in badges)
        assertEquals(2, badges.size)
    }

    @Test
    fun `no badges on empty list`() {
        assertTrue(OrderBadge.findAllBadgesInOrderBlock(emptyList()).isEmpty())
    }

    @Test
    fun `no badges on irrelevant text`() {
        val badges = OrderBadge.findAllBadgesInOrderBlock(listOf("Pickup", "Shop for items", "3 items"))
        assertTrue(badges.isEmpty())
    }

    @Test
    fun `partial text does not match - exact match only`() {
        // OrderBadge uses exact matching — "Red Card" alone should NOT match "Red Card required"
        val badges = OrderBadge.findAllBadgesInOrderBlock(listOf("Red Card"))
        assertFalse(OrderBadge.RED_CARD in badges)
    }

    @Test
    fun `duplicate text returns single badge instance`() {
        val badges = OrderBadge.findAllBadgesInOrderBlock(listOf("Red Card required", "Red Card required"))
        assertEquals(1, badges.count { it == OrderBadge.RED_CARD })
    }
}
