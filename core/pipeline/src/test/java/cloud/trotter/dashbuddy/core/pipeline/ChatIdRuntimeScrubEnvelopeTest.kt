package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.NoRedaction
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1160 reviews TT1, ZZ1, ZZ2 — envelope-level regression through the real `CaptureWriter.captureScreen`
 * with a RECORDING bus: on an UNKNOWN frame the chat header `tvTitle` and the chat preview `tvLastMessage`
 * are ALWAYS masked — whatever the value's shape, and in every serialized field.
 */
class ChatIdRuntimeScrubEnvelopeTest {

    private class RecordingBus : CaptureBus {
        val envelopes = mutableListOf<String>()
        override fun offer(
            captureId: String, source: String, classification: String?, platform: String,
            envelopeJson: String, contentHash: Int?,
        ): String? { envelopes += envelopeJson; return captureId }
    }

    private val bus = RecordingBus()
    private val writer = CaptureWriter(bus, PipelineStats(), NoRedaction)

    private fun capture(vararg nodes: UiNode): String {
        val tree = UiNode(className = "android.widget.LinearLayout", children = nodes.toList()).restoreParents()
        writer.captureScreen(
            Observation.Screen(
                timestamp = 1_000L, captureId = null, ruleId = null, metadata = ReplayMetadata.EMPTY,
                flow = null, modeHint = null, parsed = ParsedFields.None, target = UNKNOWN_TARGET,
            ),
            PipelineEvent.Screen(
                timestamp = 1_000L,
                tree = tree,
                snapshot = TreeSnapshot(tree = tree, packageName = "com.doordash.driverapp"),
                packageName = "com.doordash.driverapp",
            ),
        )
        return bus.envelopes.last()
    }

    @Test
    fun `the chat header and the chat preview are masked`() {
        val envelope = capture(
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/tvTitle", text = "Riley"),
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/tvLastMessage", text = "My gate code is 2468"),
        )
        assertTrue(envelope, !envelope.contains("Riley"))
        assertTrue(envelope, !envelope.contains("2468"))
    }

    @Test
    fun `a chrome sheet title under tvTitle is masked too - the accepted recall cost, ADR residual 11 (review ZZ1)`() {
        val chrome = capture(
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/tvTitle", text = "Pick up order"),
        )
        assertTrue(chrome, !chrome.contains("Pick up order"))
    }

    @Test
    fun `non-Latin and particle names under tvTitle are masked (review ZZ1)`() {
        listOf("李明", "محمد", "de la Cruz", "Riley S.", "Mary Jo", "RILEY S").forEach { header ->
            val envelope = capture(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/tvTitle", text = header),
            )
            assertTrue("$header: $envelope", !envelope.contains(header))
            assertTrue("$header: $envelope", envelope.contains("[redacted]"))
        }
    }

    @Test
    fun `a tvTitle carrying its name only in stateDescription is fully masked (review ZZ2)`() {
        val envelope = capture(
            UiNode(
                className = "android.widget.TextView",
                viewIdResourceName = "com.doordash.driverapp:id/tvTitle",
                text = null,
                contentDescription = null,
                stateDescription = "Riley Smith",
            ),
        )
        assertTrue(envelope, !envelope.contains("Riley"))
        assertTrue(envelope, !envelope.contains("Smith"))
        assertTrue(envelope, envelope.contains("[redacted]"))
    }

    @Test
    fun `a tag char and a combining mark cannot hide a sensitive keyword from the capture (review WW1)`() {
        val before = bus.envelopes.size
        val tree = UiNode(className = "android.widget.LinearLayout", children = listOf(
            UiNode(className = "android.widget.TextView", text = "Vis\uDB40\uDC20a\u200D\u0301 ending 6222"),
        )).restoreParents()
        writer.captureScreen(
            Observation.Screen(
                timestamp = 1_000L, captureId = null, ruleId = null, metadata = ReplayMetadata.EMPTY,
                flow = null, modeHint = null, parsed = ParsedFields.None, target = UNKNOWN_TARGET,
            ),
            PipelineEvent.Screen(
                timestamp = 1_000L, tree = tree,
                snapshot = TreeSnapshot(tree = tree, packageName = "com.doordash.driverapp"),
                packageName = "com.doordash.driverapp",
            ),
        )
        assertTrue("the sensitive frame must be dropped", bus.envelopes.size == before)
    }

    private fun captureClick(node: UiNode, recognized: Boolean = false): String {
        writer.captureClick(
            Observation.Click(
                timestamp = 1_000L, captureId = null,
                ruleId = if (recognized) "doordash.click.test" else null,
                metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null, parsed = ParsedFields.None,
                target = if (recognized) "chat_conversation" else UNKNOWN_TARGET,
            ),
            PipelineEvent.Click(timestamp = 1_000L, node = node, packageName = "com.doordash.driverapp"),
            screenTarget = null,
            screenRuleId = null,
        )
        return bus.envelopes.single()
    }

    @Test
    fun `an UNKNOWN message_input click persists a mask instead of the draft (#919)`() {
        val draft = "they only had one of the juice boxes in stock"
        val envelope = captureClick(UiNode(
            className = "android.widget.EditText",
            viewIdResourceName = "com.doordash.driverapp:id/message_input",
            text = draft,
            isClickable = true,
        ))
        assertTrue(envelope, envelope.contains("[redacted]"))
        assertTrue(envelope, !envelope.contains(draft))
    }

    @Test
    fun `an UNKNOWN id-less EditText click persists a mask instead of the draft (#919)`() {
        val draft = "they only had one of the juice boxes in stock"
        val envelope = captureClick(UiNode(
            className = "android.widget.EditText",
            text = draft,
            isClickable = true,
        ))
        assertTrue(envelope, envelope.contains("[redacted]"))
        assertTrue(envelope, !envelope.contains(draft))
    }

    @Test
    fun `a recognized EditText click keeps its rule authority with NoRedaction (#919)`() {
        val draft = "they only had one of the juice boxes in stock"
        val envelope = captureClick(UiNode(
            className = "android.widget.EditText",
            viewIdResourceName = "com.doordash.driverapp:id/message_input",
            text = draft,
            isClickable = true,
        ), recognized = true)
        assertTrue(envelope, envelope.contains(draft))
        assertTrue(envelope, !envelope.contains("[redacted]"))
    }
}
