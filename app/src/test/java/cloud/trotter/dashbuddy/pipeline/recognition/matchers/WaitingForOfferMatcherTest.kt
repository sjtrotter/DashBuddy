package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.WaitingForOfferMatcher
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitingForOfferMatcherTest {

    private val matcher = WaitingForOfferMatcher()

    // --- TEST DATA ---

    // Legacy layout: progress bar + "Looking for offers" title
    private val legacyWaitingLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
      UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
        UiNode(, id=content, state=null, class=android.widget.FrameLayout)
          UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
            UiNode(text='Looking for offers', id=no_id, state=null, class=android.widget.TextView)
            UiNode(, id=looking_for_order_progress_bar, state=null, class=android.widget.ProgressBar)
            UiNode(text='2-4 min', id=wait_time_button, state=null, class=android.widget.Button)
              UiNode(text='est. 2-4 min', id=textView_prism_button_title, state=null, class=android.widget.TextView)
            UiNode(text='${'$'}12.50', id=running_total_pay, state=null, class=android.widget.TextView)
""".trimIndent()

    // Legacy layout: heading back to zone
    private val headingBackLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Looking for offers', id=no_id, state=null, class=android.widget.TextView)
  UiNode(, id=looking_for_order_progress_bar, state=null, class=android.widget.ProgressBar)
  UiNode(text='heading back', id=cross_sp_title, state=null, class=android.widget.TextView)
  UiNode(text='${'$'}7.25', id=running_total_pay, state=null, class=android.widget.TextView)
""".trimIndent()

    // New layout: "Finding offers" text
    private val newLayoutWaitingLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Finding offers', id=no_id, state=null, class=android.widget.TextView)
  UiNode(, id=no_id, state=null, class=android.widget.ProgressBar)
""".trimIndent()

    // Guard: Idle map (Earnings Switcher present) — should NOT match
    private val idleMapLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(desc='Earnings Mode Switcher', id=no_id, state=null, class=android.view.View)
  UiNode(text='Looking for offers', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // Guard: On-dash map (Return to dash present) — should NOT match
    private val onDashMapLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Return to dash', id=no_id, state=null, class=android.widget.TextView)
  UiNode(text='Looking for offers', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // Guard: Dash Along the Way — should NOT match
    private val dashAlongWayLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text="we'll look for orders along the way", id=no_id, state=null, class=android.widget.TextView)
  UiNode(text='Looking for offers', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // --- RECOGNITION TESTS ---

    @Test
    fun `matches ON_DASH_MAP_WAITING_FOR_OFFER with legacy layout`() {
        val root = LogToUiNodeParser.parseLog(legacyWaitingLog)
        assertNotNull("Failed to parse log", root)

        val result = matcher.matches(root!!)

        assertNotNull("Should match waiting for offer screen", result)
        assertTrue(result is ScreenInfo.WaitingForOffer)
        assertEquals(Screen.ON_DASH_MAP_WAITING_FOR_OFFER, result!!.screen)
    }

    @Test
    fun `matches ON_DASH_MAP_WAITING_FOR_OFFER with new layout`() {
        val root = LogToUiNodeParser.parseLog(newLayoutWaitingLog)
        assertNotNull("Failed to parse log", root)

        val result = matcher.matches(root!!)

        assertNotNull("Should match new layout", result)
        assertEquals(Screen.ON_DASH_MAP_WAITING_FOR_OFFER, result!!.screen)
    }

    @Test
    fun `returns null when Earnings Mode Switcher present (idle map guard)`() {
        val root = LogToUiNodeParser.parseLog(idleMapLog)
        assertNotNull("Failed to parse log", root)

        assertNull("Idle map signals should block match", matcher.matches(root!!))
    }

    @Test
    fun `returns null when Return to dash present (on-dash map guard)`() {
        val root = LogToUiNodeParser.parseLog(onDashMapLog)
        assertNotNull("Failed to parse log", root)

        assertNull("On-dash map signals should block match", matcher.matches(root!!))
    }

    @Test
    fun `returns null for Dash Along the Way screen`() {
        val root = LogToUiNodeParser.parseLog(dashAlongWayLog)
        assertNotNull("Failed to parse log", root)

        assertNull(matcher.matches(root!!))
    }

    // --- PARSING TESTS ---

    @Test
    fun `parses wait time and current pay from legacy layout`() {
        val root = LogToUiNodeParser.parseLog(legacyWaitingLog)!!
        val result = matcher.matches(root) as ScreenInfo.WaitingForOffer

        assertEquals("Should parse wait time", "2-4 min", result.waitTimeEstimate)
        assertEquals("Should parse running pay", 12.50, result.currentDashPay!!, 0.01)
    }

    @Test
    fun `parses heading back to zone flag`() {
        val root = LogToUiNodeParser.parseLog(headingBackLog)!!
        val result = matcher.matches(root) as ScreenInfo.WaitingForOffer

        assertTrue("Should detect heading back state", result.isHeadingBackToZone)
        assertEquals("Should parse running pay", 7.25, result.currentDashPay!!, 0.01)
    }

    @Test
    fun `new layout returns null for volatile fields`() {
        val root = LogToUiNodeParser.parseLog(newLayoutWaitingLog)!!
        val result = matcher.matches(root) as ScreenInfo.WaitingForOffer

        assertNull("Pay volatile in new layout", result.currentDashPay)
        assertNull("Wait time not extracted in new layout", result.waitTimeEstimate)
    }
}
