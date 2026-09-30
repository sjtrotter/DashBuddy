package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.core.pipeline.CaptureWriter
import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.util.sha256OrNull
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1160 reviews XX2 / YY2 — the RECOGNIZED chat-list redact, end to end through the real
 * `CaptureWriter.captureScreen` with the PRODUCTION rule's compiled `redact`: the customer's name in
 * `tvTitle` is masked `[redacted:<hex>]` where the hex is the first 4 of the persisted
 * `customerNameHash` (#623 / #733: sha256 of the canonical customer-name key — "Riley" keys to `riley`),
 * and the customer's own message in `tvLastMessage` is masked plain `[redacted]`.
 */
class ChatListRedactEnvelopeTest {

    private class RecordingBus : CaptureBus {
        val envelopes = mutableListOf<String>()
        override fun offer(
            captureId: String, source: String, classification: String?, platform: String,
            envelopeJson: String, contentHash: Int?,
        ): String? { envelopes += envelopeJson; return captureId }
    }

    private val ruleset = TestRulesetFactory.screenRuleset
    private val bus = RecordingBus()
    private val writer = CaptureWriter(bus, PipelineStats(), object : ScreenRedactionSource {
        override fun redactFor(ruleId: String): CompiledRedact? = ruleset.ruleById(ruleId)?.redact
    })

    @Test
    fun `a recognized chat list masks the customer name with the customerNameHash hex and the message plain`() {
        val tree = UiNode(className = "android.widget.LinearLayout", children = listOf(
            UiNode(className = "android.widget.TextView", text = "Dasher"),
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/textView_navBar_title", text = "Messages"),
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/tvTitle", text = "Riley"),
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/tvLastMessage", text = "My gate code is 2468"),
        )).restoreParents()
        val match = ruleset.matchFirst(tree, platformWire = "doordash")
        assertTrue("the synthetic chat list must be RECOGNIZED (got ${match?.ruleId})", match != null)
        assertTrue(match!!.ruleId, match.ruleId == "doordash.screen.chat" || match.ruleId == "doordash.screen.chat_conversation")

        writer.captureScreen(
            Observation.Screen(
                timestamp = 1_000L, captureId = null, ruleId = match.ruleId, metadata = ReplayMetadata.EMPTY,
                flow = null, modeHint = null, parsed = ParsedFields.None, target = match.intent,
            ),
            PipelineEvent.Screen(
                timestamp = 1_000L, tree = tree,
                snapshot = TreeSnapshot(tree = tree, packageName = "com.doordash.driverapp"),
                packageName = "com.doordash.driverapp",
            ),
        )
        val envelope = bus.envelopes.single()
        assertTrue(envelope, !envelope.contains("Riley"))
        assertTrue(envelope, !envelope.contains("2468"))
        val expectedHex = sha256OrNull("riley")!!.substring(0, 4)
        assertTrue("tvTitle masked with the customerNameHash hex: $envelope", envelope.contains("[redacted:$expectedHex]"))
        assertEquals("one plain mask for the message", 1, Regex("""\[redacted]""").findAll(envelope).count())
    }
}
