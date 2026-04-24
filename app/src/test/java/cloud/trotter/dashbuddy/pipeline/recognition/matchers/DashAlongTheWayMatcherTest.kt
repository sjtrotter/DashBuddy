package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DashAlongTheWayMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.DashAlongTheWayParser
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DashAlongTheWayMatcherTest {

    private val matcher = DashAlongTheWayMatcher()
    private val parser = DashAlongTheWayParser()

    // --- TEST DATA ---

    // Standard layout: specific ctd title + navigate button
    private val alongTheWayLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
      UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
        UiNode(, id=content, state=null, class=android.widget.FrameLayout)
          UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
            UiNode(text="We'll look for orders along the way", id=bottom_view_info_ctd_v2_title, state=null, class=android.widget.TextView)
            UiNode(text='Navigate', id=navigate_button, state=null, class=android.widget.Button)
            UiNode(text='Spot saved until 3:00 PM', id=bottom_view_info_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // Fallback layout: no ctd title, but spot saved title + navigate button
    private val alongTheWayFallbackLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(text='Navigate', id=navigate_button, state=null, class=android.widget.Button)
    UiNode(text='Spot saved until 5:00 PM', id=bottom_view_info_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // Navigate button present but no info titles — should NOT match
    private val navigateOnlyLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Navigate', id=navigate_button, state=null, class=android.widget.Button)
""".trimIndent()

    // Info title present but no navigate button — should NOT match
    private val infoOnlyLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text="We'll look for orders along the way", id=bottom_view_info_ctd_v2_title, state=null, class=android.widget.TextView)
""".trimIndent()

    // --- RECOGNITION TESTS ---

    @Test
    fun `matches ON_DASH_ALONG_THE_WAY with standard layout`() {
        val root = LogToUiNodeParser.parseLog(alongTheWayLog)
        assertNotNull("Failed to parse log", root)
        assertEquals(Screen.ON_DASH_ALONG_THE_WAY, matcher.matches(root!!))
    }

    @Test
    fun `matches ON_DASH_ALONG_THE_WAY with fallback spot saved layout`() {
        val root = LogToUiNodeParser.parseLog(alongTheWayFallbackLog)
        assertNotNull("Failed to parse log", root)
        assertEquals(Screen.ON_DASH_ALONG_THE_WAY, matcher.matches(root!!))
    }

    @Test
    fun `returns null when navigate button missing`() {
        val root = LogToUiNodeParser.parseLog(infoOnlyLog)
        assertNotNull("Failed to parse log", root)

        assertNull(matcher.matches(root!!))
    }

    @Test
    fun `returns null when info title missing`() {
        val root = LogToUiNodeParser.parseLog(navigateOnlyLog)
        assertNotNull("Failed to parse log", root)

        assertNull(matcher.matches(root!!))
    }

    // --- PARSING TESTS ---

    @Test
    fun `parsed result is WaitingForOffer with isHeadingBackToZone false`() {
        val root = LogToUiNodeParser.parseLog(alongTheWayLog)!!
        val result = parser.parse(root) as ScreenInfo.WaitingForOffer

        assertFalse("Along the way is forward navigation, not heading back", result.isHeadingBackToZone)
        assertNull("Pay not available on this screen", result.dashPay)
        assertEquals("Spot save timer extracted from bottom_view_info_title", "Spot saved until 3:00 PM", result.waitTimeEstimate)
    }
}
