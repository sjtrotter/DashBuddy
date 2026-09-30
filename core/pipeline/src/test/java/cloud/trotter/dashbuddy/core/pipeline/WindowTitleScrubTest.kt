package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.NoRedaction
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1148 review rounds 1-2 — the envelope's `windowContext.windowTitle` is APP-CONTROLLED text
 * (`Activity`/`Dialog.setTitle` → `AccessibilityWindowInfo.title`) outside the tree: no tree scrub or
 * rule redact sees it, and a customer name / street line passes every marker scan. It is therefore
 * NEVER persisted (written null on every path; the hashed form is #1145). The one kept control: an
 * UNKNOWN frame whose title carries a sensitive marker is dropped (fail-closed for a title-only
 * banking dialog). A RECOGNIZED frame is never dropped on its title.
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

    /** The persisted title: null when the key is absent or JSON null. */
    private fun persistedTitle(): String? {
        val ctx = Json.parseToJsonElement(bus.envelopes.single()).jsonObject["windowContext"]!!.jsonObject
        val el = ctx["windowTitle"] ?: return null
        return if (el is JsonNull) null else (el as JsonPrimitive).content
    }

    @Test
    fun `a sensitive marker in the window title drops the capture on an UNKNOWN frame`() {
        writer.captureScreen(unknown(), event("Available balance: \$152.10"))
        assertEquals(0, bus.envelopes.size)
        assertEquals(1L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `a RECOGNIZED frame with a sensitive title is captured, title null`() {
        // The rule vetted the tree; dropping on a constant title would erase the surface.
        writer.captureScreen(recognized(), event("Available balance: \$152.10"))
        assertEquals(1, bus.envelopes.size)
        assertNull(persistedTitle())
        assertEquals(0L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `a name-shaped title is captured with the title null`() {
        writer.captureScreen(unknown(), event("Brandy S"))
        assertNull(persistedTitle())
        assertTrue("no plaintext anywhere in the envelope", "Brandy" !in bus.envelopes.single())
    }

    @Test
    fun `an address title is captured with the title null`() {
        writer.captureScreen(recognized(), event("123 Main St"))
        assertNull(persistedTitle())
        assertTrue("123 Main St" !in bus.envelopes.single())
    }

    @Test
    fun `an app-supplied redaction token does not make a title trusted`() {
        writer.captureScreen(unknown(), event("Message from Brandy S [redacted"))
        assertNull(persistedTitle())
        assertTrue("Brandy" !in bus.envelopes.single())
    }

    @Test
    fun `a benign chrome title is not persisted either`() {
        writer.captureScreen(unknown(), event("Dasher"))
        assertNull(persistedTitle())
        assertEquals(0L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `a null title stays null`() {
        writer.captureScreen(unknown(), event(null))
        assertNull(persistedTitle())
    }
}
