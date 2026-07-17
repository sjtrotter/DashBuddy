package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedact
import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedactEntry
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleCompiler
import cloud.trotter.dashbuddy.core.pipeline.rules.ScreenRedactionSource
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * #598 — rule-declared capture redaction. A recognized screen whose rule
 * declares a `redact` block must have the matched node text masked in the
 * serialized envelope offered to the bus (customer PII never persisted raw),
 * while recognition/parse/dedup keep running on the ORIGINAL tree.
 */
class CaptureRedactionTest {

    private val captureBus: CaptureBus = mock { on { isEnabled } doReturn true }
    private val stats = PipelineStats()

    /** A dropoff-shaped redact block: name node keeps its marker, address masked whole. */
    private val dropoffRedact = CompiledRedact(
        listOf(
            CompiledRedactEntry(
                find = RuleCompiler.compileNodePred(
                    Json.parseToJsonElement("""{"hasTextStartsWith":"Deliver to"}"""),
                ),
                keepPrefix = listOf("Deliver to "),
            ),
            CompiledRedactEntry(
                find = RuleCompiler.compileNodePred(
                    Json.parseToJsonElement("""{"hasIdSuffix":"address_line_1"}"""),
                ),
            ),
        ),
    )

    private fun sourceFor(ruleId: String, redact: CompiledRedact) = object : ScreenRedactionSource {
        override fun redactFor(id: String): CompiledRedact? = redact.takeIf { id == ruleId }
    }

    private fun writer(source: ScreenRedactionSource) = CaptureWriter(captureBus, stats, source)

    private fun screenEvent(tree: UiNode) = PipelineEvent.Screen(
        timestamp = 1_000L,
        tree = tree,
        snapshot = TreeSnapshot(
            tree = tree,
            packageName = "com.doordash.driverapp",
        ),
        packageName = "com.doordash.driverapp",
    )

    private fun recognizedObs(ruleId: String, target: String) = Observation.Screen(
        timestamp = 1_000L,
        captureId = null,
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.None,
        target = target,
    )

    /** Grab the envelope JSON string the writer offered to the bus. */
    private fun capturedEnvelope(): String {
        val cap = argumentCaptor<String>()
        // offer(captureId, source, classification, platform, envelopeJson, contentHash)
        org.mockito.kotlin.verify(captureBus).offer(
            any(), any(), anyOrNull(), any(), cap.capture(), anyOrNull(),
        )
        return cap.lastValue
    }

    @Test
    fun `recognized screen with redact block masks PII in the envelope`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull()))
            .thenReturn("cap-1")
        val tree = UiNode(
            children = listOf(
                UiNode(text = "Deliver to Jane Q. Doe"),
                UiNode(viewIdResourceName = "com.doordash:id/address_line_1", text = "123 Secret St"),
                UiNode(text = "Directions"),
            ),
        )
        writer(sourceFor("doordash.screen.dropoff_navigation", dropoffRedact))
            .captureScreen(recognizedObs("doordash.screen.dropoff_navigation", "dropoff_navigation"), screenEvent(tree))

        val json = capturedEnvelope()
        // PII gone, marker kept, address masked whole, unrelated node intact.
        // #623: the masked portion carries a `[redacted:<4hex>]` distinctness suffix.
        assertFalse("customer name must not persist", json.contains("Jane Q. Doe"))
        assertFalse("street address must not persist", json.contains("123 Secret St"))
        assertTrue(
            "marker + redacted name",
            Regex("""Deliver to \[redacted:[0-9a-f]{4}\]""").containsMatchIn(json),
        )
        assertTrue(
            "address masked whole",
            Regex(""""text": "\[redacted:[0-9a-f]{4}\]"""").containsMatchIn(json),
        )
        assertTrue("non-PII node intact", json.contains("Directions"))
    }

    @Test
    fun `redaction does not change the dedup contentHash - it uses the original tree`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull()))
            .thenReturn("cap-1")
        val tree = UiNode(
            children = listOf(UiNode(text = "Deliver to Jane Q. Doe")),
        )
        writer(sourceFor("doordash.screen.dropoff_navigation", dropoffRedact))
            .captureScreen(recognizedObs("doordash.screen.dropoff_navigation", "dropoff_navigation"), screenEvent(tree))

        val hashCap = argumentCaptor<Int?>()
        org.mockito.kotlin.verify(captureBus).offer(
            any(), any(), anyOrNull(), any(), any(), hashCap.capture(),
        )
        assertEquals(tree.stableHash, hashCap.lastValue)
    }

    @Test
    fun `recognized screen without a redact block persists non-PII text unchanged`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull()))
            .thenReturn("cap-1")
        // Non-PII text (no customer marker) → nothing to mask, backstop is inert.
        val tree = UiNode(children = listOf(UiNode(text = "Nearby high pay")))
        // Source returns null for this ruleId → no redaction.
        writer(sourceFor("some.other.rule", dropoffRedact))
            .captureScreen(recognizedObs("doordash.screen.idle_map", "idle_map"), screenEvent(tree))

        val json = capturedEnvelope()
        assertTrue("no redact + no marker → text persists", json.contains("Nearby high pay"))
    }

    @Test
    fun `recognized frame whose rule forgot to redact a customer marker is scrubbed by the backstop`() {
        // #624: a RECOGNIZED frame carrying "Deliver to <name>" whose rule declared
        // NO redact block (redactFor returns null) — the customer-marker backstop is
        // the only thing between the raw name and disk. It scrubs the node.
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull()))
            .thenReturn("cap-1")
        val tree = UiNode(
            children = listOf(
                UiNode(text = "Deliver to Jane Q. Doe"),
                UiNode(text = "Directions"),
            ),
        )
        writer(sourceFor("some.other.rule", dropoffRedact))
            .captureScreen(
                recognizedObs("doordash.screen.dropoff_navigation", "dropoff_navigation"),
                screenEvent(tree),
            )

        val json = capturedEnvelope()
        assertFalse("leaked customer name scrubbed by backstop", json.contains("Jane Q. Doe"))
        assertTrue("scrubbed node became [redacted]", json.contains("[redacted]"))
        assertTrue("non-PII node intact", json.contains("Directions"))
        assertEquals(1L, stats.redactBackstopScrubCount)
    }

    @Test
    fun `UNKNOWN frame is not scrubbed by the recognized-frame backstop`() {
        // The #624 backstop only guards RECOGNIZED frames; UNKNOWN frames route
        // through the SensitiveTextMarkers drop path instead. A benign UNKNOWN
        // marker-shaped string must not trip the recognized-frame scrub.
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull()))
            .thenReturn("cap-1")
        val tree = UiNode(children = listOf(UiNode(text = "Order for pickup nearby")))
        val obs = recognizedObs(UNKNOWN_TARGET, UNKNOWN_TARGET).copy(ruleId = null)
        writer(sourceFor("x", dropoffRedact)).captureScreen(obs, screenEvent(tree))
        assertTrue(capturedEnvelope().contains("Order for pickup nearby"))
        assertEquals(0L, stats.redactBackstopScrubCount)
    }

    @Test
    fun `UNKNOWN screen never consults the redaction source`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull()))
            .thenReturn("cap-1")
        val tree = UiNode(children = listOf(UiNode(text = "Some benign promo")))
        val obs = recognizedObs(UNKNOWN_TARGET, UNKNOWN_TARGET).copy(ruleId = null)
        // A throwing source would fail the test if consulted.
        val throwing = object : ScreenRedactionSource {
            override fun redactFor(ruleId: String): CompiledRedact =
                throw AssertionError("UNKNOWN path must not consult redaction source")
        }
        writer(throwing).captureScreen(obs, screenEvent(tree))
        assertTrue(capturedEnvelope().contains("Some benign promo"))
    }
}
