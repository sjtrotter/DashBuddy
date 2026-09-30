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
 * #1160 review TT1 — envelope-level regression through the real `CaptureWriter.captureScreen` with a
 * RECORDING bus: on an UNKNOWN frame the chat header `tvTitle` is masked when it reads as a name, the chat
 * preview `tvLastMessage` is always masked, and a chrome `tvTitle` ("Pick up order") survives for triage.
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
    fun `a name-like chat header and the chat preview are masked, a chrome sheet title survives`() {
        val envelope = capture(
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/tvTitle", text = "Riley"),
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/tvLastMessage", text = "My gate code is 2468"),
        )
        assertTrue(envelope, !envelope.contains("Riley"))
        assertTrue(envelope, !envelope.contains("2468"))
        val chrome = capture(
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/tvTitle", text = "Pick up order"),
        )
        assertTrue(chrome, chrome.contains("Pick up order"))
    }
}
