package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #919 — the chat compose box on a RECOGNIZED chat frame, where the runtime UNKNOWN scrub never runs and
 * the rule's `redact` is the only control (Astra P2 of PR #1227): the box masks whether it renders under
 * its `message_input` id or id-less as a bare `EditText` inside the `inputChannelView` container.
 */
class ChatComposeRedactionTest {

    private val draft = "Riley Smith wants oat milk"

    private fun chatFrame(input: UiNode): UiNode = UiNode(
        className = "android.widget.FrameLayout",
        children = listOf(
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/textView_navBar_title", text = "Morgan"),
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.doordash.driverapp:id/inputChannelView", children = listOf(input)),
        ),
    ).restoreParents()

    private fun masked(frame: UiNode): String {
        val winner = TestRulesetFactory.screenRuleset.matchFirst(frame)
        assertNotNull("the chat frame must be recognized", winner)
        assertEquals("doordash.screen.chat_conversation", winner!!.ruleId)
        return UiNodeSchema.serialize(TestRulesetFactory.screenRuleset.ruleById(winner.ruleId)!!.redact.apply(frame))
    }

    @Test
    fun `the id-bearing compose box masks on the recognized chat frame`() {
        val json = masked(chatFrame(UiNode(className = "android.widget.EditText", viewIdResourceName = "com.doordash.driverapp:id/message_input", text = draft)))
        assertFalse(json, json.contains(draft))
        assertTrue(json, json.contains("[redacted]"))
    }

    @Test
    fun `the id-less EditText compose box masks on the recognized chat frame (Astra P2)`() {
        val json = masked(chatFrame(UiNode(className = "android.widget.EditText", text = draft)))
        assertFalse(json, json.contains(draft))
        assertTrue(json, json.contains("[redacted]"))
    }

    @Test
    fun `the customer's chat title still masks through the normalized name entry`() {
        val json = masked(chatFrame(UiNode(className = "android.widget.EditText", text = draft)))
        assertFalse(json, json.contains("Morgan"))
    }
}
