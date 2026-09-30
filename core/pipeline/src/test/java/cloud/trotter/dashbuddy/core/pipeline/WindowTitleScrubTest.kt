package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedact
import cloud.trotter.dashbuddy.core.pipeline.rules.NoRedaction
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #1148 security review — the envelope's `windowContext.windowTitle` is APP-CONTROLLED text
 * (`Activity`/`Dialog.setTitle` → `AccessibilityWindowInfo.title`). Before #1148 it rode only the
 * (effectively dormant) windows-changed frames; now every content/state frame carries it, on both
 * frame classes, and it sits OUTSIDE the tree — so none of the tree scrubs (sensitive drop, rule
 * redact, customer markers) ever saw it. These pins make the title a flat scrubbed field: a
 * sensitive marker drops the capture, a customer marker masks the title, the survivor is capped.
 */
class WindowTitleScrubTest {

    private class SinkBus : CaptureBus {
        val envelopes = mutableListOf<String>()
        override fun offer(
            captureId: String, source: String, classification: String?, platform: String,
            envelopeJson: String, contentHash: Int?,
        ): String? { envelopes += envelopeJson; return captureId }
    }

    private val bus = SinkBus()
    private val stats = PipelineStats()
    private val writer = CaptureWriter(bus, stats, NoRedaction)

    private fun event(title: String?) = PipelineEvent.Screen(
        timestamp = 1_000L,
        tree = UiNode(children = listOf(UiNode(text = "Some new promo screen"))),
        snapshot = TreeSnapshot(
            tree = UiNode(children = listOf(UiNode(text = "Some new promo screen"))),
            packageName = "com.doordash.driverapp",
            windowContext = TreeSnapshot.WindowContext(
                windowId = 7, windowType = 1, windowTitle = title, windowLayer = 2,
                isActive = true, isFocused = true, totalWindowCount = 3,
            ),
        ),
        packageName = "com.doordash.driverapp",
    )

    private fun obs(target: String, ruleId: String?) = Observation.Screen(
        timestamp = 1_000L, captureId = null, ruleId = ruleId, metadata = ReplayMetadata.EMPTY,
        flow = null, modeHint = null, parsed = ParsedFields.None, target = target,
    )

    private fun unknown() = obs(UNKNOWN_TARGET, null)
    private fun recognized() = obs("idle_map", "doordash.screen.test")

    private fun persistedTitle(): String? = Json.parseToJsonElement(bus.envelopes.single())
        .jsonObject["windowContext"]!!.jsonObject["windowTitle"]?.jsonPrimitive?.content

    @Test
    fun `a sensitive marker in the window title drops the capture on an UNKNOWN frame`() {
        writer.captureScreen(unknown(), event("Available balance: \$152.10"))
        assertEquals(0, bus.envelopes.size)
        assertEquals(1L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `a sensitive marker in the window title drops the capture on a RECOGNIZED frame too`() {
        // The rule vetted the TREE. It never saw the title, so the title gets no rule credit.
        writer.captureScreen(recognized(), event("Available balance: \$152.10"))
        assertEquals(0, bus.envelopes.size)
        assertEquals(1L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `a customer marker in the window title is masked, the frame still captures (UNKNOWN)`() {
        writer.captureScreen(unknown(), event("Deliver to Brandy S"))
        assertEquals(CompiledRedact.REDACTED, persistedTitle())
        assertEquals(1L, stats.unknownCustomerScrubCount)
    }

    @Test
    fun `a customer marker in the window title is masked on a RECOGNIZED frame as well`() {
        writer.captureScreen(recognized(), event("Message from Brandy S"))
        assertEquals(CompiledRedact.REDACTED, persistedTitle())
        assertEquals(1L, stats.redactBackstopScrubCount)
    }

    @Test
    fun `a benign chrome title persists verbatim`() {
        writer.captureScreen(unknown(), event("Dasher"))
        assertEquals("Dasher", persistedTitle())
        assertEquals(0L, stats.unknownCustomerScrubCount)
    }

    @Test
    fun `an over-long title is capped at MAX_WINDOW_TITLE_LENGTH`() {
        val long = "T".repeat(CaptureWriter.MAX_WINDOW_TITLE_LENGTH + 40)
        writer.captureScreen(unknown(), event(long))
        assertEquals(CaptureWriter.MAX_WINDOW_TITLE_LENGTH, persistedTitle()!!.length)
    }

    @Test
    fun `a null title stays null`() {
        writer.captureScreen(unknown(), event(null))
        assertNull(persistedTitle())
    }
}
