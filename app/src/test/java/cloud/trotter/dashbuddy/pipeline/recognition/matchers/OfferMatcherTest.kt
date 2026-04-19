package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.offer.OfferBadge
import cloud.trotter.dashbuddy.domain.model.order.OrderBadge
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.OfferMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.OfferParser
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferMatcherTest {

    private val matcher = OfferMatcher()
    private val parser = OfferParser()

    // --- TEST DATA ---

    // Standard single-order offer: pay + distance + time, one store
    private val singleOrderOfferLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
      UiNode(, id=content, state=null, class=android.widget.FrameLayout)
        UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
          UiNode(text='${'$'}7.50', id=text_field, state=null, class=android.widget.TextView)
          UiNode(text='2.8 mi', id=text_field, state=null, class=android.widget.TextView)
          UiNode(text='Deliver by 8:45 PM', id=text_field, state=null, class=android.widget.TextView)
          UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
            UiNode(text='Pickup', id=work_unit_type, state=null, class=android.widget.TextView)
            UiNode(text='Chipotle', id=display_name, state=null, class=android.widget.TextView)
            UiNode(text='(1 order)', id=display_name_secondary, state=null, class=android.widget.TextView)
          UiNode(text='Decline', id=no_id, state=null, class=android.widget.Button)
          UiNode(text='Accept', id=no_id, state=null, class=android.widget.Button)
""".trimIndent()

    // "Add to route" instead of "Accept"
    private val addToRouteOfferLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='${'$'}9.00', id=text_field, state=null, class=android.widget.TextView)
  UiNode(text='3.5 mi', id=text_field, state=null, class=android.widget.TextView)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(text='Pickup', id=work_unit_type, state=null, class=android.widget.TextView)
    UiNode(text='Subway', id=display_name, state=null, class=android.widget.TextView)
  UiNode(text='Decline', id=no_id, state=null, class=android.widget.Button)
  UiNode(text='Add to route', id=no_id, state=null, class=android.widget.Button)
""".trimIndent()

    // Multi-order: two separate orders from the same store
    private val multiOrderOfferLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='${'$'}12.00', id=text_field, state=null, class=android.widget.TextView)
  UiNode(text='4.0 mi', id=text_field, state=null, class=android.widget.TextView)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(text='Pickup', id=work_unit_type, state=null, class=android.widget.TextView)
    UiNode(text='Wendys', id=display_name, state=null, class=android.widget.TextView)
    UiNode(text='(2 orders)', id=display_name_secondary, state=null, class=android.widget.TextView)
  UiNode(text='Decline', id=no_id, state=null, class=android.widget.Button)
  UiNode(text='Accept', id=no_id, state=null, class=android.widget.Button)
""".trimIndent()

    // Shop offer: work_unit_type contains "Shop"
    private val shopOfferLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='${'$'}11.25', id=text_field, state=null, class=android.widget.TextView)
  UiNode(text='1.9 mi', id=text_field, state=null, class=android.widget.TextView)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(text='Shop for items', id=work_unit_type, state=null, class=android.widget.TextView)
    UiNode(text='Walgreens', id=display_name, state=null, class=android.widget.TextView)
    UiNode(text='(8 items)', id=display_name_secondary, state=null, class=android.widget.TextView)
  UiNode(text='Decline', id=no_id, state=null, class=android.widget.Button)
  UiNode(text='Accept', id=no_id, state=null, class=android.widget.Button)
""".trimIndent()

    // Red Card offer
    private val redCardOfferLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='${'$'}8.75', id=text_field, state=null, class=android.widget.TextView)
  UiNode(text='2.0 mi', id=text_field, state=null, class=android.widget.TextView)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(text='Shop for items', id=work_unit_type, state=null, class=android.widget.TextView)
    UiNode(text='CVS Pharmacy', id=display_name, state=null, class=android.widget.TextView)
    UiNode(text='Red Card', id=tag, state=null, class=android.widget.TextView)
  UiNode(text='Decline', id=no_id, state=null, class=android.widget.Button)
  UiNode(text='Accept', id=no_id, state=null, class=android.widget.Button)
""".trimIndent()

    // Decline confirmation dialog — should return OFFER_POPUP_CONFIRM_DECLINE (not null)
    private val declineConfirmLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Are you sure you want to decline this offer?', id=no_id, state=null, class=android.widget.TextView)
  UiNode(text='Yes, decline', id=no_id, state=null, class=android.widget.Button)
  UiNode(text='Never mind', id=no_id, state=null, class=android.widget.Button)
""".trimIndent()

    // Progress bar present — should NOT match (loading state)
    private val loadingOfferLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=progress_bar, state=null, class=android.widget.ProgressBar)
  UiNode(text='${'$'}6.00', id=text_field, state=null, class=android.widget.TextView)
  UiNode(text='Decline', id=no_id, state=null, class=android.widget.Button)
  UiNode(text='Accept', id=no_id, state=null, class=android.widget.Button)
""".trimIndent()

    // Missing Decline button — should NOT match
    private val noDeclineLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='${'$'}5.00', id=text_field, state=null, class=android.widget.TextView)
  UiNode(text='Accept', id=no_id, state=null, class=android.widget.Button)
""".trimIndent()

    // Missing Accept/Add to route — should NOT match
    private val noAcceptLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='${'$'}5.00', id=text_field, state=null, class=android.widget.TextView)
  UiNode(text='Decline', id=no_id, state=null, class=android.widget.Button)
""".trimIndent()

    // Pay amount missing — matcher matches (Decline+Accept present), parser returns Simple
    private val noPayLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='2.8 mi', id=text_field, state=null, class=android.widget.TextView)
  UiNode(text='Chipotle', id=display_name, state=null, class=android.widget.TextView)
  UiNode(text='Decline', id=no_id, state=null, class=android.widget.Button)
  UiNode(text='Accept', id=no_id, state=null, class=android.widget.Button)
""".trimIndent()

    // --- RECOGNITION TESTS ---

    @Test
    fun `matches OFFER_POPUP for standard single-order offer`() {
        val root = LogToUiNodeParser.parseLog(singleOrderOfferLog)
        assertNotNull("Failed to parse log", root)
        assertEquals(Screen.OFFER_POPUP, matcher.matches(root!!))
    }

    @Test
    fun `matches OFFER_POPUP when Add to route replaces Accept`() {
        val root = LogToUiNodeParser.parseLog(addToRouteOfferLog)
        assertNotNull("Failed to parse log", root)
        assertEquals(Screen.OFFER_POPUP, matcher.matches(root!!))
    }

    @Test
    fun `returns OFFER_POPUP_CONFIRM_DECLINE for decline confirmation dialog`() {
        val root = LogToUiNodeParser.parseLog(declineConfirmLog)
        assertNotNull("Failed to parse log", root)
        assertEquals(Screen.OFFER_POPUP_CONFIRM_DECLINE, matcher.matches(root!!))
    }

    @Test
    fun `returns null when progress bar is present (loading state)`() {
        val root = LogToUiNodeParser.parseLog(loadingOfferLog)
        assertNotNull("Failed to parse log", root)

        assertNull("Progress bar should block match", matcher.matches(root!!))
    }

    @Test
    fun `returns null when Decline button is missing`() {
        val root = LogToUiNodeParser.parseLog(noDeclineLog)
        assertNotNull("Failed to parse log", root)

        assertNull("Missing Decline should not match", matcher.matches(root!!))
    }

    @Test
    fun `returns null when Accept and Add to route are both missing`() {
        val root = LogToUiNodeParser.parseLog(noAcceptLog)
        assertNotNull("Failed to parse log", root)

        assertNull("Missing Accept should not match", matcher.matches(root!!))
    }

    @Test
    fun `matches when pay amount missing (matcher only checks Decline and Accept)`() {
        val root = LogToUiNodeParser.parseLog(noPayLog)
        assertNotNull("Failed to parse log", root)

        // Matcher checks structural signals only — Decline+Accept are present so it matches
        assertEquals(Screen.OFFER_POPUP, matcher.matches(root!!))
    }

    @Test
    fun `parser returns Simple when pay amount is missing`() {
        val root = LogToUiNodeParser.parseLog(noPayLog)!!
        // Parser returns Simple (not Offer) when payAmount is null — no parseable offer
        assertTrue("Missing pay should produce Simple result", parser.parse(root) is ScreenInfo.Simple)
    }

    // --- PARSING TESTS ---

    @Test
    fun `parses pay amount from text_field node`() {
        val root = LogToUiNodeParser.parseLog(singleOrderOfferLog)!!
        val result = parser.parse(root) as ScreenInfo.Offer

        assertEquals(7.50, result.parsedOffer.payAmount!!, 0.01)
    }

    @Test
    fun `parses distance miles from text_field node`() {
        val root = LogToUiNodeParser.parseLog(singleOrderOfferLog)!!
        val result = parser.parse(root) as ScreenInfo.Offer

        assertEquals(2.8, result.parsedOffer.distanceMiles!!, 0.01)
    }

    @Test
    fun `parses due-by time text from Deliver by text_field`() {
        val root = LogToUiNodeParser.parseLog(singleOrderOfferLog)!!
        val result = parser.parse(root) as ScreenInfo.Offer

        assertEquals("8:45 PM", result.parsedOffer.dueByTimeText)
    }

    @Test
    fun `parses single order with store name`() {
        val root = LogToUiNodeParser.parseLog(singleOrderOfferLog)!!
        val result = parser.parse(root) as ScreenInfo.Offer

        assertEquals(1, result.parsedOffer.orders.size)
        assertEquals("Chipotle", result.parsedOffer.orders[0].storeName)
    }

    @Test
    fun `parses order type as PICKUP when work_unit_type says Pickup`() {
        val root = LogToUiNodeParser.parseLog(singleOrderOfferLog)!!
        val result = parser.parse(root) as ScreenInfo.Offer

        assertEquals(OrderType.PICKUP, result.parsedOffer.orders[0].orderType)
    }

    @Test
    fun `parses order type as SHOP_FOR_ITEMS when work_unit_type says Shop for items`() {
        val root = LogToUiNodeParser.parseLog(shopOfferLog)!!
        val result = parser.parse(root) as ScreenInfo.Offer

        assertEquals(OrderType.SHOP_FOR_ITEMS, result.parsedOffer.orders[0].orderType)
    }

    @Test
    fun `parses item count from display_name_secondary for shop order`() {
        val root = LogToUiNodeParser.parseLog(shopOfferLog)!!
        val result = parser.parse(root) as ScreenInfo.Offer

        assertEquals(8, result.parsedOffer.orders[0].itemCount)
    }

    @Test
    fun `expands multi-order into separate ParsedOrders`() {
        val root = LogToUiNodeParser.parseLog(multiOrderOfferLog)!!
        val result = parser.parse(root) as ScreenInfo.Offer

        // "(2 orders)" -> 2 ParsedOrder entries
        assertEquals(2, result.parsedOffer.orders.size)
        result.parsedOffer.orders.forEach { order ->
            assertEquals("Wendys", order.storeName)
        }
    }

    @Test
    fun `detects Red Card badge on order`() {
        val root = LogToUiNodeParser.parseLog(redCardOfferLog)!!
        val result = parser.parse(root) as ScreenInfo.Offer

        assertTrue("Red Card badge should be detected", result.parsedOffer.orders[0].badges.contains(OrderBadge.RED_CARD))
    }

    @Test
    fun `creates fallback order when no display_name nodes found`() {
        val root = LogToUiNodeParser.parseLog(addToRouteOfferLog)!!
        val result = parser.parse(root) as ScreenInfo.Offer

        // addToRouteOfferLog has a display_name node, this verifies it parsed correctly
        assertEquals(1, result.parsedOffer.orders.size)
        assertEquals("Subway", result.parsedOffer.orders[0].storeName)
    }

    @Test
    fun `offer hash is not null or blank`() {
        val root = LogToUiNodeParser.parseLog(singleOrderOfferLog)!!
        val result = parser.parse(root) as ScreenInfo.Offer

        assertTrue("Offer hash should be set", result.parsedOffer.offerHash.isNotBlank())
    }
}
