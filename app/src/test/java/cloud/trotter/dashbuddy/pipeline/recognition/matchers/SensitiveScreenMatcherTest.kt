package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.SensitiveScreenMatcher
import cloud.trotter.dashbuddy.test.LogToUiNodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveScreenMatcherTest {

    private val matcher = SensitiveScreenMatcher()

    // --- TEST DATA ---

    private fun buildLog(text: String) = """
UiNode(, id=no_id, state=null, class=android.widget.FrameLayout)
  UiNode(text='$text', id=no_id, state=null, class=android.widget.TextView)
""".trimIndent()

    private val safeLog = buildLog("Looking for offers")

    // --- RECOGNITION TESTS ---

    @Test
    fun `matches SENSITIVE when Bank Account keyword present`() {
        val root = LogToUiNodeParser.parseLog(buildLog("Bank Account"))!!
        val result = matcher.matches(root)
        assertNotNull(result)
        assertTrue(result is ScreenInfo.Sensitive)
        assertEquals(Screen.SENSITIVE, result!!.screen)
    }

    @Test
    fun `matches SENSITIVE when Crimson keyword present`() {
        val root = LogToUiNodeParser.parseLog(buildLog("Crimson Debit Account"))!!
        assertNotNull(matcher.matches(root))
    }

    @Test
    fun `matches SENSITIVE when Available Balance keyword present`() {
        val root = LogToUiNodeParser.parseLog(buildLog("Available Balance"))!!
        assertNotNull(matcher.matches(root))
    }

    @Test
    fun `matches SENSITIVE when Routing Number keyword present`() {
        val root = LogToUiNodeParser.parseLog(buildLog("Routing Number"))!!
        assertNotNull(matcher.matches(root))
    }

    @Test
    fun `matches SENSITIVE case insensitively`() {
        val root = LogToUiNodeParser.parseLog(buildLog("bank account"))!!
        assertNotNull("Keyword match should be case insensitive", matcher.matches(root))
    }

    @Test
    fun `returns null for safe screen`() {
        val root = LogToUiNodeParser.parseLog(safeLog)!!
        assertNull(matcher.matches(root))
    }

    @Test
    fun `all keywords in companion object produce a match`() {
        SensitiveScreenMatcher.SENSITIVE_KEYWORDS.forEach { keyword ->
            val root = LogToUiNodeParser.parseLog(buildLog(keyword))!!
            assertNotNull("Keyword '$keyword' should trigger sensitive match", matcher.matches(root))
        }
    }
}
