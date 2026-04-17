package cloud.trotter.dashbuddy.domain.model.offer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferBadgeTest {

    // -------------------------------------------------------------------------
    // Exact match badges
    // -------------------------------------------------------------------------

    @Test
    fun `exact match - ALL_ORDERS_SAME_STORE detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("All orders are from the same store"))
        assertTrue(OfferBadge.ALL_ORDERS_SAME_STORE in badges)
    }

    @Test
    fun `exact match - case insensitive`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("ALL ORDERS ARE FROM THE SAME STORE"))
        assertTrue(OfferBadge.ALL_ORDERS_SAME_STORE in badges)
    }

    @Test
    fun `exact match - BOTH_ORDERS_SAME_CUSTOMER detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("Both orders go to the same customer"))
        assertTrue(OfferBadge.BOTH_ORDERS_SAME_CUSTOMER in badges)
    }

    @Test
    fun `exact match - SHARPIE_RECOMMENDED detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("Black marker or Sharpie recommended"))
        assertTrue(OfferBadge.SHARPIE_RECOMMENDED in badges)
    }

    @Test
    fun `exact match - AGE_RESTRICTED_18_PLUS detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("Must be 18+ to accept order"))
        assertTrue(OfferBadge.AGE_RESTRICTED_18_PLUS in badges)
    }

    @Test
    fun `exact match - AGE_RESTRICTED_21_PLUS detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("Must be 21+ to accept order"))
        assertTrue(OfferBadge.AGE_RESTRICTED_21_PLUS in badges)
    }

    @Test
    fun `exact match - CHECK_RECIPIENT_ID detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("Check recipient's ID"))
        assertTrue(OfferBadge.CHECK_RECIPIENT_ID in badges)
    }

    @Test
    fun `exact match - COLLECT_CASH detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("Collect cash from customer"))
        assertTrue(OfferBadge.COLLECT_CASH in badges)
    }

    @Test
    fun `exact match - MAY_NEED_RETURNS detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("May need returns"))
        assertTrue(OfferBadge.MAY_NEED_RETURNS in badges)
    }

    @Test
    fun `exact match - CONTAINS_RESTRICTED_ITEMS detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("Contains restricted items"))
        assertTrue(OfferBadge.CONTAINS_RESTRICTED_ITEMS in badges)
    }

    @Test
    fun `exact match - ITEMS_CAN_BE_ADDED detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("Items can be added before checkout"))
        assertTrue(OfferBadge.ITEMS_CAN_BE_ADDED in badges)
    }

    // -------------------------------------------------------------------------
    // Contains match badges
    // -------------------------------------------------------------------------

    @Test
    fun `contains match - INCLUDES_ALCOHOL detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("This order, including alcohol, will be delivered"))
        assertTrue(OfferBadge.INCLUDES_ALCOHOL in badges)
    }

    @Test
    fun `contains match - INCLUDES_ALCOHOL case insensitive`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("Including Alcohol in this order"))
        assertTrue(OfferBadge.INCLUDES_ALCOHOL in badges)
    }

    // -------------------------------------------------------------------------
    // Regex match badges
    // -------------------------------------------------------------------------

    @Test
    fun `regex match - HIGH_PAYING detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("High-paying offer!"))
        assertTrue(OfferBadge.HIGH_PAYING in badges)
    }

    @Test
    fun `regex match - HIGH_PAYING shopping variant detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("High-paying shopping offer!"))
        assertTrue(OfferBadge.HIGH_PAYING in badges)
    }

    @Test
    fun `regex match - PRIORITY_ACCESS Platinum detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("Your Platinum status gave you priority access"))
        assertTrue(OfferBadge.PRIORITY_ACCESS in badges)
    }

    @Test
    fun `regex match - PRIORITY_ACCESS Gold detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("your Gold status gives you this"))
        assertTrue(OfferBadge.PRIORITY_ACCESS in badges)
    }

    @Test
    fun `regex match - PRIORITY_ACCESS Silver detected`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("your Silver status gives you priority"))
        assertTrue(OfferBadge.PRIORITY_ACCESS in badges)
    }

    // -------------------------------------------------------------------------
    // Multiple badges in a single screen
    // -------------------------------------------------------------------------

    @Test
    fun `multiple badges detected across screen texts`() {
        val screenTexts = listOf(
            "High-paying offer!",
            "Must be 21+ to accept order",
            "Collect cash from customer",
        )
        val badges = OfferBadge.findAllBadgesInScreen(screenTexts)
        assertTrue(OfferBadge.HIGH_PAYING in badges)
        assertTrue(OfferBadge.AGE_RESTRICTED_21_PLUS in badges)
        assertTrue(OfferBadge.COLLECT_CASH in badges)
        assertEquals(3, badges.size)
    }

    @Test
    fun `contains badge detected within a longer line`() {
        // INCLUDES_ALCOHOL uses containsText, so it matches as a substring in a longer line
        val badges = OfferBadge.findAllBadgesInScreen(listOf("This order, including alcohol, will be delivered"))
        assertTrue(OfferBadge.INCLUDES_ALCOHOL in badges)
    }

    @Test
    fun `exact badge does not match when line has extra content`() {
        // CONTAINS_RESTRICTED_ITEMS uses exactMatchText — it will NOT match a longer line
        val badges = OfferBadge.findAllBadgesInScreen(listOf("Contains restricted items, including alcohol"))
        assertFalse(OfferBadge.CONTAINS_RESTRICTED_ITEMS in badges)
        // But INCLUDES_ALCOHOL (containsText) does match
        assertTrue(OfferBadge.INCLUDES_ALCOHOL in badges)
    }

    // -------------------------------------------------------------------------
    // No match
    // -------------------------------------------------------------------------

    @Test
    fun `no badges on empty screen texts`() {
        val badges = OfferBadge.findAllBadgesInScreen(emptyList())
        assertTrue(badges.isEmpty())
    }

    @Test
    fun `no badges on irrelevant texts`() {
        val badges = OfferBadge.findAllBadgesInScreen(listOf("Pickup", "3.2 mi", "\$7.50 Guaranteed"))
        assertTrue(badges.isEmpty())
    }

    @Test
    fun `partial text does not match exact badge`() {
        // "same store" alone should NOT match "All orders are from the same store"
        val badges = OfferBadge.findAllBadgesInScreen(listOf("same store"))
        assertFalse(OfferBadge.ALL_ORDERS_SAME_STORE in badges)
    }

    // -------------------------------------------------------------------------
    // Deduplication
    // -------------------------------------------------------------------------

    @Test
    fun `same badge text appearing twice returns only one badge instance`() {
        val screenTexts = listOf("High-paying offer!", "High-paying offer!")
        val badges = OfferBadge.findAllBadgesInScreen(screenTexts)
        assertEquals(1, badges.count { it == OfferBadge.HIGH_PAYING })
    }
}
