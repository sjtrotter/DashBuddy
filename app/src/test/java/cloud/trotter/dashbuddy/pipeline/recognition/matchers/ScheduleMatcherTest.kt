package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.ScheduleMatcher
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleMatcherTest {

    private val matcher = ScheduleMatcher()

    // --- TEST DATA ---

    // Standard schedule view with both tabs
    private val scheduleLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
      UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
        UiNode(, id=content, state=null, class=android.widget.FrameLayout)
          UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
            UiNode(text='Schedule', id=no_id, state=null, class=android.widget.TextView)
            UiNode(, id=no_id, state=null, class=android.widget.TabLayout)
              UiNode(text='Available', id=no_id, state=null, class=android.widget.TextView)
              UiNode(text='Scheduled', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // Schedule view with only "Available" tab visible
    private val scheduleAvailableOnlyLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(text='Schedule', id=no_id, state=null, class=android.widget.TextView)
    UiNode(text='Available', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // Has "Schedule" text but no tabs — should NOT match (e.g., side nav "Schedule" menu item)
    private val sideNavScheduleLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Schedule', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    private val unrelatedLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Looking for offers', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // --- RECOGNITION TESTS ---

    @Test
    fun `matches SCHEDULE_VIEW with both tabs present`() {
        val root = LogToUiNodeParser.parseLog(scheduleLog)
        assertNotNull("Failed to parse log", root)
        assertEquals(Screen.SCHEDULE_VIEW, matcher.matches(root!!))
    }

    @Test
    fun `matches SCHEDULE_VIEW with only Available tab`() {
        val root = LogToUiNodeParser.parseLog(scheduleAvailableOnlyLog)
        assertNotNull("Failed to parse log", root)
        assertEquals(Screen.SCHEDULE_VIEW, matcher.matches(root!!))
    }

    @Test
    fun `returns null when Schedule title present but no tabs`() {
        val root = LogToUiNodeParser.parseLog(sideNavScheduleLog)
        assertNotNull("Failed to parse log", root)

        assertNull("Side nav Schedule item should not match", matcher.matches(root!!))
    }

    @Test
    fun `returns null for unrelated screen`() {
        val root = LogToUiNodeParser.parseLog(unrelatedLog)
        assertNotNull("Failed to parse log", root)

        assertNull(matcher.matches(root!!))
    }
}
