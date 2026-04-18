package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.order.DropoffStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DropoffNavigationMatcher
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DropoffNavigationMatcherTest {

    private val matcher = DropoffNavigationMatcher()

    // --- TEST DATA ---

    // Standard dropoff navigation
    private val dropoffNavLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
      UiNode(, id=content, state=null, class=android.widget.FrameLayout)
        UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
          UiNode(text='Deliver to John D', id=bottom_sheet_task_title, state=null, class=android.widget.TextView)
          UiNode(text='Deliver by 4:00 PM', id=bottom_sheet_task_arrive_by, state=null, class=android.widget.TextView)
          UiNode(text='456 Oak Ave', id=bottom_sheet_address_line_1, state=null, class=android.widget.TextView)
          UiNode(text='Austin, TX 78703', id=bottom_sheet_address_line_2, state=null, class=android.widget.TextView)
""".trimIndent()

    // Pickup navigation — same ID, different text — should NOT match
    private val pickupNavLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Heading to McDonald''s', id=bottom_sheet_task_title, state=null, class=android.widget.TextView)
  UiNode(text='Pick up by 3:15 PM', id=bottom_sheet_task_arrive_by, state=null, class=android.widget.TextView)
  UiNode(text='123 Main St', id=bottom_sheet_address_line_1, state=null, class=android.widget.TextView)
""".trimIndent()

    // Unknown title text — should NOT match (fail-safe)
    private val unknownTitleLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Something else entirely', id=bottom_sheet_task_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // Navigation title ID missing — should NOT match
    private val noTitleIdLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Deliver to Jane S', id=no_id, state=null, class=android.widget.TextView)
  UiNode(text='789 Elm St', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // Address missing — customer hash still present
    private val noAddressLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Deliver to Sam W', id=bottom_sheet_task_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // --- RECOGNITION TESTS ---

    @Test
    fun `matches NAVIGATION_VIEW_TO_DROP_OFF for dropoff navigation`() {
        val root = LogToUiNodeParser.parseLog(dropoffNavLog)
        assertNotNull("Failed to parse log", root)

        val result = matcher.matches(root!!)

        assertNotNull("Should match dropoff navigation", result)
        assertTrue(result is ScreenInfo.DropoffDetails)
        assertEquals(Screen.NAVIGATION_VIEW_TO_DROP_OFF, result!!.screen)
    }

    @Test
    fun `returns null for pickup navigation (Heading to prefix)`() {
        val root = LogToUiNodeParser.parseLog(pickupNavLog)
        assertNotNull("Failed to parse log", root)

        assertNull("Pickup navigation should not match dropoff matcher", matcher.matches(root!!))
    }

    @Test
    fun `returns null when title text is neither Deliver to nor Heading to`() {
        val root = LogToUiNodeParser.parseLog(unknownTitleLog)
        assertNotNull("Failed to parse log", root)

        assertNull("Unknown title text should not match", matcher.matches(root!!))
    }

    @Test
    fun `returns null when navigation title ID is missing`() {
        val root = LogToUiNodeParser.parseLog(noTitleIdLog)
        assertNotNull("Failed to parse log", root)

        assertNull("Missing title node ID should not match", matcher.matches(root!!))
    }

    // --- PARSING TESTS ---

    @Test
    fun `status is NAVIGATING`() {
        val root = LogToUiNodeParser.parseLog(dropoffNavLog)!!
        val result = matcher.matches(root) as ScreenInfo.DropoffDetails

        assertEquals(DropoffStatus.NAVIGATING, result.status)
    }

    @Test
    fun `hashes customer name extracted from Deliver to prefix`() {
        val root = LogToUiNodeParser.parseLog(dropoffNavLog)!!
        val result = matcher.matches(root) as ScreenInfo.DropoffDetails

        // Name "John D" extracted from "Deliver to John D" — verified as SHA-256
        assertNotNull("Customer name hash should be present", result.customerNameHash)
        assertEquals("SHA-256 hash is 64 hex chars", 64, result.customerNameHash!!.length)
    }

    @Test
    fun `hashes address from address lines`() {
        val root = LogToUiNodeParser.parseLog(dropoffNavLog)!!
        val result = matcher.matches(root) as ScreenInfo.DropoffDetails

        assertNotNull("Address hash should be present when address nodes exist", result.addressHash)
        assertEquals("SHA-256 hash is 64 hex chars", 64, result.addressHash!!.length)
    }

    @Test
    fun `address hash is null when no address nodes present`() {
        val root = LogToUiNodeParser.parseLog(noAddressLog)!!
        val result = matcher.matches(root) as ScreenInfo.DropoffDetails

        assertNull("Address hash should be null if no address nodes", result.addressHash)
    }

    @Test
    fun `customer hash is null when name part is blank`() {
        val log = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Deliver to ', id=bottom_sheet_task_title, state=null, class=android.widget.TextView)
""".trimIndent()
        val root = LogToUiNodeParser.parseLog(log)!!
        val result = matcher.matches(root) as ScreenInfo.DropoffDetails

        assertNull("Blank name after prefix should produce null hash", result.customerNameHash)
    }
}
