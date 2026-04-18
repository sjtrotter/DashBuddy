package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.SetDashEndTimeMatcher
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetDashEndTimeMatcherTest {

    private val matcher = SetDashEndTimeMatcher()

    // --- TEST DATA ---

    private val setEndTimeLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
      UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
        UiNode(, id=content, state=null, class=android.widget.FrameLayout)
          UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
            UiNode(text='TX: Leon Valley', id=starting_point_name, state=null, class=android.widget.TextView)
            UiNode(text='Select end time', id=no_id, state=null, class=android.widget.TextView)
            UiNode(text='Dashers needed until 9:00 PM', id=end_time_description, state=null, class=android.widget.TextView)
""".trimIndent()

    // Fallback: zone node + end time description only (no "Select end time" text)
    private val setEndTimeDescOnlyLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='TX: Leon Valley', id=starting_point_name, state=null, class=android.widget.TextView)
  UiNode(text='Dashers needed until 9:00 PM', id=end_time_description, state=null, class=android.widget.TextView)
""".trimIndent()

    // Zone node present but neither "Select end time" nor description — should NOT match
    private val zoneOnlyLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='TX: Leon Valley', id=starting_point_name, state=null, class=android.widget.TextView)
""".trimIndent()

    // Neither anchor — should NOT match
    private val unrelatedLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Looking for offers', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // --- RECOGNITION TESTS ---

    @Test
    fun `matches SET_DASH_END_TIME with zone name and select time text`() {
        val root = LogToUiNodeParser.parseLog(setEndTimeLog)
        assertNotNull("Failed to parse log", root)

        val result = matcher.matches(root!!)

        assertNotNull("Should match set dash end time screen", result)
        assertTrue(result is ScreenInfo.IdleMap)
        assertEquals(Screen.SET_DASH_END_TIME, result!!.screen)
    }

    @Test
    fun `matches SET_DASH_END_TIME with zone name and description only`() {
        val root = LogToUiNodeParser.parseLog(setEndTimeDescOnlyLog)
        assertNotNull("Failed to parse log", root)

        val result = matcher.matches(root!!)

        assertNotNull("Should match with description fallback", result)
        assertEquals(Screen.SET_DASH_END_TIME, result!!.screen)
    }

    @Test
    fun `returns null when zone node present but no time context`() {
        val root = LogToUiNodeParser.parseLog(zoneOnlyLog)
        assertNotNull("Failed to parse log", root)

        assertNull(matcher.matches(root!!))
    }

    @Test
    fun `returns null for unrelated screen`() {
        val root = LogToUiNodeParser.parseLog(unrelatedLog)
        assertNotNull("Failed to parse log", root)

        assertNull(matcher.matches(root!!))
    }

    // --- PARSING TESTS ---

    @Test
    fun `parses zone name from starting_point_name node`() {
        val root = LogToUiNodeParser.parseLog(setEndTimeLog)!!
        val result = matcher.matches(root) as ScreenInfo.IdleMap

        assertEquals("TX: Leon Valley", result.zoneName)
    }

    @Test
    fun `dash type is always null on this screen`() {
        val root = LogToUiNodeParser.parseLog(setEndTimeLog)!!
        val result = matcher.matches(root) as ScreenInfo.IdleMap

        assertNull("DashType toggle not visible on this screen", result.dashType)
    }
}
