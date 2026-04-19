package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupNavigationMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.PickupNavigationParser
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PickupNavigationMatcherTest {

    private val matcher = PickupNavigationMatcher()
    private val parser = PickupNavigationParser()

    // --- TEST DATA ---

    private val pickupNavLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
      UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
        UiNode(, id=content, state=null, class=android.widget.FrameLayout)
          UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
            UiNode(text='Heading to Chipotle', id=bottom_sheet_task_title, state=null, class=android.widget.TextView)
            UiNode(text='Pick up by 3:15 PM', id=bottom_sheet_task_arrive_by, state=null, class=android.widget.TextView)
            UiNode(text='123 Main St', id=bottom_sheet_address_line_1, state=null, class=android.widget.TextView)
            UiNode(text='Austin, TX 78701', id=bottom_sheet_address_line_2, state=null, class=android.widget.TextView)
""".trimIndent()

    // Dropoff navigation — same ID, different text — should NOT match
    private val dropoffNavLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Deliver to John D', id=bottom_sheet_task_title, state=null, class=android.widget.TextView)
  UiNode(text='Deliver by 3:30 PM', id=bottom_sheet_task_arrive_by, state=null, class=android.widget.TextView)
  UiNode(text='456 Oak Ave', id=bottom_sheet_address_line_1, state=null, class=android.widget.TextView)
""".trimIndent()

    // "Deliver by" in arrive-by node — double fail-fast
    private val deliverByTimeLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Heading to Walgreens', id=bottom_sheet_task_title, state=null, class=android.widget.TextView)
  UiNode(text='Deliver by 4:00 PM', id=bottom_sheet_task_arrive_by, state=null, class=android.widget.TextView)
""".trimIndent()

    // No navigation title node — should NOT match
    private val noTitleLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Heading somewhere', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // --- RECOGNITION TESTS ---

    @Test
    fun `matches NAVIGATION_VIEW_TO_PICK_UP for pickup navigation`() {
        val root = LogToUiNodeParser.parseLog(pickupNavLog)
        assertNotNull("Failed to parse log", root)
        assertEquals(Screen.NAVIGATION_VIEW_TO_PICK_UP, matcher.matches(root!!))
    }

    @Test
    fun `returns null for dropoff navigation (Deliver to)`() {
        val root = LogToUiNodeParser.parseLog(dropoffNavLog)
        assertNotNull("Failed to parse log", root)

        assertNull("Dropoff navigation should not match", matcher.matches(root!!))
    }

    @Test
    fun `returns null when arrive-by node says Deliver by`() {
        val root = LogToUiNodeParser.parseLog(deliverByTimeLog)
        assertNotNull("Failed to parse log", root)

        assertNull("Deliver by in time node should fail-fast", matcher.matches(root!!))
    }

    @Test
    fun `returns null when navigation title ID is missing`() {
        val root = LogToUiNodeParser.parseLog(noTitleLog)
        assertNotNull("Failed to parse log", root)

        assertNull(matcher.matches(root!!))
    }

    // --- PARSING TESTS ---

    @Test
    fun `parses store name from Heading to prefix`() {
        val root = LogToUiNodeParser.parseLog(pickupNavLog)!!
        val result = parser.parse(root) as ScreenInfo.PickupDetails

        assertEquals("Chipotle", result.storeName)
    }

    @Test
    fun `parses full address from address lines`() {
        val root = LogToUiNodeParser.parseLog(pickupNavLog)!!
        val result = parser.parse(root) as ScreenInfo.PickupDetails

        assertEquals("123 Main St, Austin, TX 78701", result.storeAddress)
    }

    @Test
    fun `status is NAVIGATING`() {
        val root = LogToUiNodeParser.parseLog(pickupNavLog)!!
        val result = parser.parse(root) as ScreenInfo.PickupDetails

        assertEquals(PickupStatus.NAVIGATING, result.status)
    }

    @Test
    fun `address is null when no address nodes present`() {
        val log = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Heading to Subway', id=bottom_sheet_task_title, state=null, class=android.widget.TextView)
""".trimIndent()
        val root = LogToUiNodeParser.parseLog(log)!!
        val result = parser.parse(root) as ScreenInfo.PickupDetails

        assertNull("Address should be null if no address nodes", result.storeAddress)
    }
}
