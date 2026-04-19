package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupPreArrivalMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.PickupPreArrivalParser
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PickupPreArrivalMatcherTest {

    private val matcher = PickupPreArrivalMatcher()
    private val parser = PickupPreArrivalParser()

    // --- TEST DATA ---

    // Standard pre-arrival: "Directions" button, store + address present
    private val preArrivalDirectionsLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
      UiNode(, id=content, state=null, class=android.widget.FrameLayout)
        UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
          UiNode(text='Pickup from', id=user_name_label, state=null, class=android.widget.TextView)
          UiNode(text='Chipotle', id=user_name, state=null, class=android.widget.TextView)
          UiNode(text='350 Congress Ave', id=address_line_1, state=null, class=android.widget.TextView)
          UiNode(text='Austin, TX 78701', id=address_line_2, state=null, class=android.widget.TextView)
          UiNode(, id=no_id, state=null, class=android.widget.Button)
            UiNode(text='Directions', id=textView_prism_button_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // "Arrived at store" button — still pre-arrival (button not yet clicked)
    private val preArrivalArrivedButtonLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Pickup from', id=user_name_label, state=null, class=android.widget.TextView)
  UiNode(text='Subway', id=user_name, state=null, class=android.widget.TextView)
  UiNode(text='100 E 5th St', id=address_line_1, state=null, class=android.widget.TextView)
  UiNode(text='Austin, TX 78701', id=address_line_2, state=null, class=android.widget.TextView)
  UiNode(, id=no_id, state=null, class=android.widget.Button)
    UiNode(text='Arrived at store', id=textView_prism_button_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // Wrong label text — "Delivery for" instead of "Pickup from" — should NOT match
    private val deliveryForLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Delivery for', id=user_name_label, state=null, class=android.widget.TextView)
  UiNode(text='John D', id=user_name, state=null, class=android.widget.TextView)
  UiNode(text='999 Oak St', id=address_line_1, state=null, class=android.widget.TextView)
  UiNode(, id=no_id, state=null, class=android.widget.Button)
    UiNode(text='Directions', id=textView_prism_button_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // Label correct but wrong button text — should NOT match
    private val wrongButtonLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Pickup from', id=user_name_label, state=null, class=android.widget.TextView)
  UiNode(text='Starbucks', id=user_name, state=null, class=android.widget.TextView)
  UiNode(text='200 Lavaca St', id=address_line_1, state=null, class=android.widget.TextView)
  UiNode(, id=no_id, state=null, class=android.widget.Button)
    UiNode(text='Confirm pickup', id=textView_prism_button_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // Missing store name (user_name absent) — should NOT match
    private val noStoreNameLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Pickup from', id=user_name_label, state=null, class=android.widget.TextView)
  UiNode(text='500 Congress Ave', id=address_line_1, state=null, class=android.widget.TextView)
  UiNode(text='Austin, TX 78701', id=address_line_2, state=null, class=android.widget.TextView)
  UiNode(, id=no_id, state=null, class=android.widget.Button)
    UiNode(text='Directions', id=textView_prism_button_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // Address line 1 = store name (duplicate) — should use only line 2
    private val duplicateAddressLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Pickup from', id=user_name_label, state=null, class=android.widget.TextView)
  UiNode(text='Wendy''s', id=user_name, state=null, class=android.widget.TextView)
  UiNode(text='Wendy''s', id=address_line_1, state=null, class=android.widget.TextView)
  UiNode(text='Austin, TX 78704', id=address_line_2, state=null, class=android.widget.TextView)
  UiNode(, id=no_id, state=null, class=android.widget.Button)
    UiNode(text='Directions', id=textView_prism_button_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // --- RECOGNITION TESTS ---

    @Test
    fun `matches PICKUP_DETAILS_PRE_ARRIVAL with Directions button`() {
        val root = LogToUiNodeParser.parseLog(preArrivalDirectionsLog)
        assertNotNull("Failed to parse log", root)
        assertEquals(Screen.PICKUP_DETAILS_PRE_ARRIVAL, matcher.matches(root!!))
    }

    @Test
    fun `matches PICKUP_DETAILS_PRE_ARRIVAL with Arrived at store button`() {
        val root = LogToUiNodeParser.parseLog(preArrivalArrivedButtonLog)
        assertNotNull("Failed to parse log", root)
        assertEquals(Screen.PICKUP_DETAILS_PRE_ARRIVAL, matcher.matches(root!!))
    }

    @Test
    fun `returns null when label says Delivery for (not Pickup from)`() {
        val root = LogToUiNodeParser.parseLog(deliveryForLog)
        assertNotNull("Failed to parse log", root)

        assertNull("Delivery label should not match pickup pre-arrival", matcher.matches(root!!))
    }

    @Test
    fun `returns null when button text is not Directions or Arrived at store`() {
        val root = LogToUiNodeParser.parseLog(wrongButtonLog)
        assertNotNull("Failed to parse log", root)

        assertNull("Wrong button text should not match", matcher.matches(root!!))
    }

    @Test
    fun `still matches when store name node is missing (data completeness is a parser concern)`() {
        val root = LogToUiNodeParser.parseLog(noStoreNameLog)
        assertNotNull("Failed to parse log", root)

        // The matcher identifies the SCREEN TYPE based on structural cues (label + button).
        // A missing user_name node does not change what screen this is.
        assertEquals(Screen.PICKUP_DETAILS_PRE_ARRIVAL, matcher.matches(root!!))
    }

    // --- PARSING TESTS ---

    @Test
    fun `parses store name from user_name node`() {
        val root = LogToUiNodeParser.parseLog(preArrivalDirectionsLog)!!
        val result = parser.parse(root) as ScreenInfo.PickupDetails

        assertEquals("Chipotle", result.storeName)
    }

    @Test
    fun `parses full address from address lines`() {
        val root = LogToUiNodeParser.parseLog(preArrivalDirectionsLog)!!
        val result = parser.parse(root) as ScreenInfo.PickupDetails

        assertEquals("350 Congress Ave, Austin, TX 78701", result.storeAddress)
    }

    @Test
    fun `status is always NAVIGATING regardless of button text`() {
        val root = LogToUiNodeParser.parseLog(preArrivalArrivedButtonLog)!!
        val result = parser.parse(root) as ScreenInfo.PickupDetails

        assertEquals(PickupStatus.NAVIGATING, result.status)
    }

    @Test
    fun `skips address line 1 when it duplicates the store name`() {
        val root = LogToUiNodeParser.parseLog(duplicateAddressLog)!!
        val result = parser.parse(root) as ScreenInfo.PickupDetails

        // address_line_1 == storeName ("Wendy's"), so only line_2 should appear
        assertEquals("Austin, TX 78704", result.storeAddress)
    }
}
