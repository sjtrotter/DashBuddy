package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupArrivalMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.PickupArrivalParser
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PickupArrivalMatcherTest {

    private val matcher = PickupArrivalMatcher()
    private val parser = PickupArrivalParser()

    // --- TEST DATA ---

    // Standard arrival: "Order for" label + "Confirm pickup" button + store in instructions_title
    private val arrivalConfirmLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
      UiNode(, id=content, state=null, class=android.widget.FrameLayout)
        UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
          UiNode(text='Order for', id=customer_name_label, state=null, class=android.widget.TextView)
          UiNode(text='Alex J.', id=customer_name, state=null, class=android.widget.TextView)
          UiNode(text='Chipotle', id=instructions_title, state=null, class=android.widget.TextView)
          UiNode(, id=no_id, state=null, class=android.widget.Button)
            UiNode(text='Confirm pickup', id=textView_prism_button_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // "Continue with pickup" button variant
    private val arrivalContinueLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Order for', id=customer_name_label, state=null, class=android.widget.TextView)
  UiNode(text='Sam K.', id=customer_name, state=null, class=android.widget.TextView)
  UiNode(text='Subway', id=instructions_title, state=null, class=android.widget.TextView)
  UiNode(, id=no_id, state=null, class=android.widget.Button)
    UiNode(text='Continue with pickup', id=textView_prism_button_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // "Start pickup" button variant
    private val arrivalStartLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Order for', id=customer_name_label, state=null, class=android.widget.TextView)
  UiNode(text='Jordan M.', id=customer_name, state=null, class=android.widget.TextView)
  UiNode(, id=no_id, state=null, class=android.widget.Button)
    UiNode(text='Start pickup', id=textView_prism_button_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // instructions_title says "Parking instructions" — store name should be null
    private val parkingInstructionsLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Order for', id=customer_name_label, state=null, class=android.widget.TextView)
  UiNode(text='Casey L.', id=customer_name, state=null, class=android.widget.TextView)
  UiNode(text='Parking instructions', id=instructions_title, state=null, class=android.widget.TextView)
  UiNode(, id=no_id, state=null, class=android.widget.Button)
    UiNode(text='Confirm pickup', id=textView_prism_button_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // Wrong label — "Pickup from" instead of "Order for" — should NOT match
    private val wrongLabelLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Pickup from', id=customer_name_label, state=null, class=android.widget.TextView)
  UiNode(text='Starbucks', id=customer_name, state=null, class=android.widget.TextView)
  UiNode(, id=no_id, state=null, class=android.widget.Button)
    UiNode(text='Confirm pickup', id=textView_prism_button_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // Wrong button text — should NOT match
    private val wrongButtonLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Order for', id=customer_name_label, state=null, class=android.widget.TextView)
  UiNode(text='Pat R.', id=customer_name, state=null, class=android.widget.TextView)
  UiNode(, id=no_id, state=null, class=android.widget.Button)
    UiNode(text='Directions', id=textView_prism_button_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // --- RECOGNITION TESTS ---

    @Test
    fun `matches PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE with Confirm pickup button`() {
        val root = LogToUiNodeParser.parseLog(arrivalConfirmLog)
        assertNotNull("Failed to parse log", root)
        assertEquals(Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE, matcher.matches(root!!))
    }

    @Test
    fun `matches with Continue with pickup button`() {
        val root = LogToUiNodeParser.parseLog(arrivalContinueLog)
        assertNotNull("Failed to parse log", root)
        assertEquals(Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE, matcher.matches(root!!))
    }

    @Test
    fun `matches with Start pickup button`() {
        val root = LogToUiNodeParser.parseLog(arrivalStartLog)
        assertNotNull("Failed to parse log", root)
        assertEquals(Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE, matcher.matches(root!!))
    }

    @Test
    fun `returns null when label is not Order for`() {
        val root = LogToUiNodeParser.parseLog(wrongLabelLog)
        assertNotNull("Failed to parse log", root)

        assertNull("Wrong label should not match", matcher.matches(root!!))
    }

    @Test
    fun `returns null when button text is not Confirm, Continue, or Start`() {
        val root = LogToUiNodeParser.parseLog(wrongButtonLog)
        assertNotNull("Failed to parse log", root)

        assertNull("Wrong button text should not match", matcher.matches(root!!))
    }

    // --- PARSING TESTS ---

    @Test
    fun `status is always ARRIVED`() {
        val root = LogToUiNodeParser.parseLog(arrivalConfirmLog)!!
        val result = parser.parse(root) as ScreenInfo.PickupDetails

        assertEquals(PickupStatus.ARRIVED, result.status)
    }

    @Test
    fun `parses customer name hash from customer_name node`() {
        val root = LogToUiNodeParser.parseLog(arrivalConfirmLog)!!
        val result = parser.parse(root) as ScreenInfo.PickupDetails

        // Name is hashed — we can only verify it's a 64-char hex SHA-256
        assertNotNull("Customer name hash should not be null", result.customerNameHash)
        assertEquals("SHA-256 hash is 64 hex chars", 64, result.customerNameHash!!.length)
    }

    @Test
    fun `parses store name from instructions_title when not parking instructions`() {
        val root = LogToUiNodeParser.parseLog(arrivalConfirmLog)!!
        val result = parser.parse(root) as ScreenInfo.PickupDetails

        assertEquals("Chipotle", result.storeName)
    }

    @Test
    fun `store name is null when instructions_title contains instructions keyword`() {
        val root = LogToUiNodeParser.parseLog(parkingInstructionsLog)!!
        val result = parser.parse(root) as ScreenInfo.PickupDetails

        assertNull("Parking instructions should not be used as store name", result.storeName)
    }

    @Test
    fun `store name is null when instructions_title is absent`() {
        val root = LogToUiNodeParser.parseLog(arrivalStartLog)!!
        val result = parser.parse(root) as ScreenInfo.PickupDetails

        assertNull("Missing instructions_title should result in null store name", result.storeName)
    }
}
