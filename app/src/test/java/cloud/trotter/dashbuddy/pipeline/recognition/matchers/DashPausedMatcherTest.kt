package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DashPausedMatcher
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class DashPausedMatcherTest {

    private val matcher = DashPausedMatcher()

    // --- TEST DATA ---

    // Standard dash paused with 34:15 remaining
    private val dashPausedLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
      UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
        UiNode(, id=content, state=null, class=android.widget.FrameLayout)
          UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
            UiNode(text='Dash Paused', id=no_id, state=null, class=android.widget.TextView)
            UiNode(text='34:15', id=progress_number, state=null, class=android.widget.TextView)
            UiNode(desc='Resume dash', id=resumeButton, state=null, class=android.widget.Button)
""".trimIndent()

    // Only 1 minute remaining
    private val dashPausedNearEndLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Dash Paused', id=no_id, state=null, class=android.widget.TextView)
  UiNode(text='01:00', id=progress_number, state=null, class=android.widget.TextView)
  UiNode(desc='Resume dash', id=resumeButton, state=null, class=android.widget.Button)
""".trimIndent()

    // Missing "Dash Paused" title — should NOT match
    private val noTitleLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='34:15', id=progress_number, state=null, class=android.widget.TextView)
  UiNode(desc='Resume dash', id=resumeButton, state=null, class=android.widget.Button)
""".trimIndent()

    // Missing resume button — should NOT match
    private val noButtonLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Dash Paused', id=no_id, state=null, class=android.widget.TextView)
  UiNode(text='34:15', id=progress_number, state=null, class=android.widget.TextView)
""".trimIndent()

    // Missing progress number — should NOT match
    private val noTimerLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Dash Paused', id=no_id, state=null, class=android.widget.TextView)
  UiNode(desc='Resume dash', id=resumeButton, state=null, class=android.widget.Button)
""".trimIndent()

    // --- RECOGNITION TESTS ---

    @Test
    fun `matches DASH_PAUSED when all anchors present`() {
        val root = LogToUiNodeParser.parseLog(dashPausedLog)
        assertNotNull("Failed to parse log", root)

        val result = matcher.matches(root!!)

        assertNotNull("Should match dash paused screen", result)
        assertTrue(result is ScreenInfo.DashPaused)
        assertEquals(Screen.DASH_PAUSED, result!!.screen)
    }

    @Test
    fun `returns null when title is missing`() {
        val root = LogToUiNodeParser.parseLog(noTitleLog)
        assertNotNull("Failed to parse log", root)

        assertNull(matcher.matches(root!!))
    }

    @Test
    fun `returns null when resume button is missing`() {
        val root = LogToUiNodeParser.parseLog(noButtonLog)
        assertNotNull("Failed to parse log", root)

        assertNull(matcher.matches(root!!))
    }

    @Test
    fun `returns null when progress number is missing`() {
        val root = LogToUiNodeParser.parseLog(noTimerLog)
        assertNotNull("Failed to parse log", root)

        assertNull(matcher.matches(root!!))
    }

    // --- PARSING TESTS ---

    @Test
    fun `parses remaining time correctly for 34m 15s`() {
        val root = LogToUiNodeParser.parseLog(dashPausedLog)!!
        val result = matcher.matches(root) as ScreenInfo.DashPaused

        val expectedMillis = TimeUnit.MINUTES.toMillis(34) + TimeUnit.SECONDS.toMillis(15)
        assertEquals("Should parse 34:15 as milliseconds", expectedMillis, result.remainingMillis)
        assertEquals("Raw time text should be preserved", "34:15", result.rawTimeText)
    }

    @Test
    fun `parses remaining time correctly for 01m 00s`() {
        val root = LogToUiNodeParser.parseLog(dashPausedNearEndLog)!!
        val result = matcher.matches(root) as ScreenInfo.DashPaused

        val expectedMillis = TimeUnit.MINUTES.toMillis(1)
        assertEquals("Should parse 01:00 as 60000ms", expectedMillis, result.remainingMillis)
        assertEquals("Raw time text should be preserved", "01:00", result.rawTimeText)
    }

    @Test
    fun `parses remaining time correctly for zero seconds`() {
        val log = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Dash Paused', id=no_id, state=null, class=android.widget.TextView)
  UiNode(text='00:00', id=progress_number, state=null, class=android.widget.TextView)
  UiNode(desc='Resume dash', id=resumeButton, state=null, class=android.widget.Button)
""".trimIndent()
        val root = LogToUiNodeParser.parseLog(log)!!
        val result = matcher.matches(root) as ScreenInfo.DashPaused

        assertEquals("00:00 should parse to 0ms", 0L, result.remainingMillis)
    }
}
