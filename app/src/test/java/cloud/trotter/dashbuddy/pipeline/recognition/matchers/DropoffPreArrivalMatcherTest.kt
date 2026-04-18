package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.order.DropoffStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DropoffPreArrivalMatcher
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DropoffPreArrivalMatcherTest {

    private val matcher = DropoffPreArrivalMatcher()

    // --- TEST DATA ---

    // Pre-arrival: "Deliver to" header + "Directions" button (still navigating)
    private val preArrivalDirectionsLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
      UiNode(, id=content, state=null, class=android.widget.FrameLayout)
        UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
          UiNode(text='Deliver to Sam H', id=no_id, state=null, class=android.widget.TextView)
          UiNode(text='Directions', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // Arrived: "Continue" button
    private val arrivedContinueLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Deliver to Jamie L', id=no_id, state=null, class=android.widget.TextView)
  UiNode(text='Continue', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // Arrived: "Complete Delivery" button
    private val completeDeliveryLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Deliver to Pat R', id=no_id, state=null, class=android.widget.TextView)
  UiNode(text='Complete Delivery', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // Contact buttons only (no action button)
    private val contactOnlyLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Deliver to Riley T', id=no_id, state=null, class=android.widget.TextView)
  UiNode(text='Call', id=no_id, state=null, class=android.widget.TextView)
  UiNode(text='Message', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // "Deliver to" header but no action or contact buttons — should NOT match
    private val noButtonsLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Deliver to Morgan K', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // No "Deliver to" header at all — should NOT match
    private val unrelatedLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Heading to Subway', id=no_id, state=null, class=android.widget.TextView)
  UiNode(text='Directions', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // --- RECOGNITION TESTS ---

    @Test
    fun `matches DROPOFF_DETAILS_PRE_ARRIVAL with Deliver to header and Directions button`() {
        val root = LogToUiNodeParser.parseLog(preArrivalDirectionsLog)
        assertNotNull("Failed to parse log", root)

        val result = matcher.matches(root!!)

        assertNotNull("Should match dropoff pre-arrival screen", result)
        assertTrue(result is ScreenInfo.DropoffDetails)
        assertEquals(Screen.DROPOFF_DETAILS_PRE_ARRIVAL, result!!.screen)
    }

    @Test
    fun `matches DROPOFF_DETAILS_PRE_ARRIVAL with Continue button`() {
        val root = LogToUiNodeParser.parseLog(arrivedContinueLog)
        assertNotNull("Failed to parse log", root)

        val result = matcher.matches(root!!)

        assertNotNull("Should match with Continue button", result)
        assertEquals(Screen.DROPOFF_DETAILS_PRE_ARRIVAL, result!!.screen)
    }

    @Test
    fun `matches DROPOFF_DETAILS_PRE_ARRIVAL with Complete Delivery button`() {
        val root = LogToUiNodeParser.parseLog(completeDeliveryLog)
        assertNotNull("Failed to parse log", root)

        val result = matcher.matches(root!!)

        assertNotNull("Should match with Complete Delivery button", result)
        assertEquals(Screen.DROPOFF_DETAILS_PRE_ARRIVAL, result!!.screen)
    }

    @Test
    fun `matches when only contact buttons present (Call or Message)`() {
        val root = LogToUiNodeParser.parseLog(contactOnlyLog)
        assertNotNull("Failed to parse log", root)

        val result = matcher.matches(root!!)

        assertNotNull("Contact buttons alone should be enough to match", result)
        assertEquals(Screen.DROPOFF_DETAILS_PRE_ARRIVAL, result!!.screen)
    }

    @Test
    fun `returns null when Deliver to header present but no buttons`() {
        val root = LogToUiNodeParser.parseLog(noButtonsLog)
        assertNotNull("Failed to parse log", root)

        assertNull("No buttons should block match", matcher.matches(root!!))
    }

    @Test
    fun `returns null for unrelated screen without Deliver to header`() {
        val root = LogToUiNodeParser.parseLog(unrelatedLog)
        assertNotNull("Failed to parse log", root)

        assertNull("Missing Deliver to header should not match", matcher.matches(root!!))
    }

    // --- PARSING TESTS ---

    @Test
    fun `status is NAVIGATING when Directions button is present`() {
        val root = LogToUiNodeParser.parseLog(preArrivalDirectionsLog)!!
        val result = matcher.matches(root) as ScreenInfo.DropoffDetails

        assertEquals(DropoffStatus.NAVIGATING, result.status)
    }

    @Test
    fun `status is ARRIVED when Continue button is present`() {
        val root = LogToUiNodeParser.parseLog(arrivedContinueLog)!!
        val result = matcher.matches(root) as ScreenInfo.DropoffDetails

        assertEquals(DropoffStatus.ARRIVED, result.status)
    }

    @Test
    fun `status is ARRIVED when Complete Delivery button is present`() {
        val root = LogToUiNodeParser.parseLog(completeDeliveryLog)!!
        val result = matcher.matches(root) as ScreenInfo.DropoffDetails

        assertEquals(DropoffStatus.ARRIVED, result.status)
    }

    @Test
    fun `status is UNKNOWN when only contact buttons present`() {
        val root = LogToUiNodeParser.parseLog(contactOnlyLog)!!
        val result = matcher.matches(root) as ScreenInfo.DropoffDetails

        assertEquals(DropoffStatus.UNKNOWN, result.status)
    }

    @Test
    fun `hashes customer name extracted from Deliver to header`() {
        val root = LogToUiNodeParser.parseLog(preArrivalDirectionsLog)!!
        val result = matcher.matches(root) as ScreenInfo.DropoffDetails

        // "Sam H" extracted from "Deliver to Sam H" — verified as SHA-256
        assertNotNull("Customer name hash should not be null", result.customerNameHash)
        assertEquals("SHA-256 hash is 64 hex chars", 64, result.customerNameHash!!.length)
    }

    @Test
    fun `address hash is always null (unstable IDs, not extracted)`() {
        val root = LogToUiNodeParser.parseLog(preArrivalDirectionsLog)!!
        val result = matcher.matches(root) as ScreenInfo.DropoffDetails

        assertNull("Address hash is explicitly null (unstable IDs)", result.addressHash)
    }
}
