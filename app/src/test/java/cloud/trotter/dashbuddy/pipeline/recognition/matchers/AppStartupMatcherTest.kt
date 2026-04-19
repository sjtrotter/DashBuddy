package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.AppStartupMatcher
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppStartupMatcherTest {

    private val matcher = AppStartupMatcher()

    // --- TEST DATA ---

    private val startingLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
      UiNode(, id=action_bar_root, state=null, class=android.widget.LinearLayout)
        UiNode(, id=content, state=null, class=android.widget.FrameLayout)
          UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
            UiNode(text='Starting…', id=no_id, state=null, class=android.widget.TextView)
            UiNode(text='Cancel', id=no_id, state=null, class=android.widget.Button)
""".trimIndent()

    // Missing "Cancel" button — should not match
    private val startingNoCancelLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(, id=no_id, state=null, class=android.widget.LinearLayout)
    UiNode(text='Starting…', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // Neither anchor present
    private val unrelatedLog = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='Some other screen', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    // --- RECOGNITION TESTS ---

    @Test
    fun `matches APP_STARTING_OR_LOADING when Starting text and Cancel button present`() {
        val root = LogToUiNodeParser.parseLog(startingLog)
        assertNotNull("Failed to parse log", root)
        assertEquals(Screen.APP_STARTING_OR_LOADING, matcher.matches(root!!))
    }

    @Test
    fun `returns null when Starting text present but Cancel button missing`() {
        val root = LogToUiNodeParser.parseLog(startingNoCancelLog)
        assertNotNull("Failed to parse log", root)

        assertNull(matcher.matches(root!!))
    }

    @Test
    fun `returns null for unrelated screen`() {
        val root = LogToUiNodeParser.parseLog(unrelatedLog)
        assertNotNull("Failed to parse log", root)

        assertNull(matcher.matches(root!!))
    }
}
