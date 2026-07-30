package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedact
import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedactEntry
import cloud.trotter.dashbuddy.core.pipeline.rules.NoRedaction
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleCompiler
import cloud.trotter.dashbuddy.core.pipeline.rules.ScreenRedactionSource
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * #835 — `stateDescription` joins every scrub/redact layer.
 *
 * The field is captured (SDK ≥ R, `AccessibilityNodeMapper`) and serialized as
 * `"state"`, but every layer hand-listed `text` + `contentDescription`, so a
 * third-party view that mirrors content into `stateDescription` (toggles,
 * sliders and custom controls do) would ship it verbatim on BOTH the recognized
 * (rule `redact`) and the UNKNOWN (marker backstop) paths.
 *
 * Latent, not fielded: no committed fixture puts PII there today. These tests
 * are the standing proof that all four layers now read the one
 * [UiNode.scrubbableStrings] SSOT — several iterate [UiNodeTextField.entries] so
 * a field added to the enum without a scrub site fails HERE.
 */
class StateDescriptionScrubTest {

    private val captureBus: CaptureBus = mock { on { isEnabled } doReturn true }
    private val stats = PipelineStats()

    private fun screenEvent(tree: UiNode) = PipelineEvent.Screen(
        timestamp = 1_000L,
        tree = tree,
        snapshot = TreeSnapshot(tree = tree, packageName = "com.doordash.driverapp"),
        packageName = "com.doordash.driverapp",
    )

    private fun obs(ruleId: String?, target: String) = Observation.Screen(
        timestamp = 1_000L,
        captureId = null,
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.None,
        target = target,
    )

    private fun unknownClickObs() = Observation.Click(
        timestamp = 1_000L,
        captureId = null,
        ruleId = null,
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.None,
        target = UNKNOWN_TARGET,
    )

    private fun sourceFor(forRuleId: String, redact: CompiledRedact) = object : ScreenRedactionSource {
        override fun redactFor(ruleId: String): CompiledRedact? = redact.takeIf { ruleId == forRuleId }
    }

    private fun writer(source: ScreenRedactionSource = NoRedaction) =
        CaptureWriter(captureBus, stats, source)

    private fun capturedEnvelope(): String {
        val cap = argumentCaptor<String>()
        verify(captureBus).offer(any(), any(), anyOrNull(), any(), cap.capture(), anyOrNull())
        return cap.lastValue
    }

    /**
     * A node carrying [value] in exactly ONE field. The `when` is exhaustive on
     * purpose: adding a [UiNodeTextField] entry breaks compilation here until the
     * new field is given a builder, so the per-field sweeps below cannot silently
     * skip it.
     */
    private fun nodeCarrying(field: UiNodeTextField, value: String, id: String? = null) = when (field) {
        UiNodeTextField.TEXT -> UiNode(text = value, viewIdResourceName = id)
        UiNodeTextField.CONTENT_DESCRIPTION -> UiNode(contentDescription = value, viewIdResourceName = id)
        UiNodeTextField.STATE_DESCRIPTION -> UiNode(stateDescription = value, viewIdResourceName = id)
    }

    // =========================================================================
    // (a) the rule-declared redact — the recognized path
    // =========================================================================

    /** A dropoff-shaped block: whatever node carries the address id is masked whole. */
    private val addressRedact = CompiledRedact(
        listOf(
            CompiledRedactEntry(
                find = RuleCompiler.compileNodePred(
                    Json.parseToJsonElement("""{"hasIdSuffix":"address_line_1"}"""),
                ),
            ),
        ),
    )

    @Test
    fun `a redact entry masks a PII-bearing stateDescription on the matched node`() {
        busAcceptsOffer()
        val tree = UiNode(
            children = listOf(
                UiNode(
                    viewIdResourceName = "com.doordash:id/address_line_1",
                    text = "123 Secret St",
                    stateDescription = "123 Secret St, Springfield ZZ 78254",
                ),
                UiNode(text = "Directions"),
            ),
        )

        writer(sourceFor("doordash.screen.dropoff_navigation", addressRedact))
            .captureScreen(obs("doordash.screen.dropoff_navigation", "dropoff_navigation"), screenEvent(tree))

        val json = capturedEnvelope()
        assertFalse("street address must not persist in text", json.contains("123 Secret St"))
        assertFalse(
            "street address must not persist in stateDescription (#835)",
            json.contains("Springfield ZZ 78254"),
        )
        assertTrue(
            "the state field is masked, not dropped",
            Regex(""""state": "\[redacted(:[0-9a-f]{4})?]"""").containsMatchIn(json),
        )
        assertTrue("non-PII node intact", json.contains("Directions"))
    }

    @Test
    fun `the redact mask covers every scrubbable field of a matched node`() {
        // The tripwire: ALL fields set on one matched node, none may survive raw.
        val matched = UiNode(
            viewIdResourceName = "com.doordash:id/address_line_1",
            text = "PII-IN-TEXT",
            contentDescription = "PII-IN-DESC",
            stateDescription = "PII-IN-STATE",
        )
        val masked = addressRedact.apply(matched)

        for ((field, value) in masked.scrubbableStrings()) {
            assertNotNull("$field must still be present (masked, not dropped)", value)
            assertTrue(
                "$field must be masked by the redact block",
                value!!.startsWith("[redacted"),
            )
        }
    }

    @Test
    fun `an unmatched node keeps every field, state included`() {
        val untouched = UiNode(
            viewIdResourceName = "com.doordash:id/store_name",
            text = "SPROUTS FARMERS MARKET #118",
            stateDescription = "Selected",
        )
        assertEquals(untouched.scrubbableStrings(), addressRedact.apply(untouched).scrubbableStrings())
    }

    // =========================================================================
    // (b) the customer-marker backstop — recognized AND UNKNOWN paths
    // =========================================================================

    @Test
    fun `the customer text-marker scan sees a marker in any scrubbable field`() {
        for (field in UiNodeTextField.entries) {
            val tree = UiNode(children = listOf(nodeCarrying(field, "Deliver to Jane Q. Doe")))
            assertEquals(
                "marker riding $field must be found",
                "Deliver to ",
                CustomerTextMarkers.firstUnredactedMarker(tree),
            )
            val scrubbed = CustomerTextMarkers.scrub(tree)
            assertFalse(
                "marker riding $field must be scrubbed",
                scrubbed.allScrubbableText().any { it.contains("Jane Q. Doe") },
            )
        }
    }

    @Test
    fun `an UNKNOWN screen carrying a customer marker in stateDescription is scrubbed, not shipped raw`() {
        busAcceptsOffer()
        val tree = UiNode(
            children = listOf(
                UiNode(text = "Current task"),
                UiNode(stateDescription = "Deliver to Jane Q. Doe"),
            ),
        )

        writer().captureScreen(obs(null, UNKNOWN_TARGET), screenEvent(tree))

        val json = capturedEnvelope()
        assertFalse("customer name must not persist", json.contains("Jane Q. Doe"))
        assertTrue("the frame still captures for triage", json.contains("Current task"))
        assertEquals(1L, stats.unknownCustomerScrubCount)
    }

    @Test
    fun `the customer-PII node-id scan treats a state-only value as still raw`() {
        for (field in UiNodeTextField.entries) {
            val node = nodeCarrying(field, "Jane Q. Doe", id = "com.doordash:id/user_name")
            assertEquals(
                "a customer-PII id whose value rides $field must scrub",
                "user_name",
                CustomerTextMarkers.unredactedIdMarker(node),
            )
            val scrubbed = CustomerTextMarkers.scrubUnknown(node)
            assertFalse(
                "the id scrub must mask $field",
                scrubbed.allScrubbableText().any { it.contains("Jane Q. Doe") },
            )
        }
    }

    @Test
    fun `an already-masked node is not re-scrubbed regardless of which field holds the mask`() {
        for (field in UiNodeTextField.entries) {
            val node = nodeCarrying(field, CompiledRedact.REDACTED, id = "com.doordash:id/user_name")
            assertNull(
                "an all-masked node must not re-trip the id scan via $field",
                CustomerTextMarkers.unredactedIdMarker(node),
            )
        }
    }

    // =========================================================================
    // (c) the dasher-sensitive backstop — the whole capture is DROPPED
    // =========================================================================

    @Test
    fun `a sensitive marker riding stateDescription is found by the fail-closed scan`() {
        for (field in UiNodeTextField.entries) {
            val tree = UiNode(children = listOf(nodeCarrying(field, "Available Balance")))
            assertEquals(
                "a banking marker in $field must drop the capture",
                "Available Balance",
                SensitiveTextMarkers.findMarker(tree),
            )
        }
    }

    @Test
    fun `an UNKNOWN screen whose sensitive marker rides stateDescription is never offered to the bus`() {
        val toxic = UiNode(children = listOf(UiNode(stateDescription = "Routing Number 021000021")))
        writer().captureScreen(obs(null, UNKNOWN_TARGET), screenEvent(toxic))

        verify(captureBus, never()).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertEquals(1L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `an UNKNOWN click whose sensitive marker rides stateDescription is never offered to the bus`() {
        val toxic = UiNode(text = "Confirm", stateDescription = "Transfer out", isClickable = true)
        writer().captureClick(
            unknownClickObs(),
            PipelineEvent.Click(timestamp = 1_000L, node = toxic, packageName = "com.doordash.driverapp"),
            screenTarget = null,
            screenRuleId = null,
        )

        verify(captureBus, never()).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertEquals(1L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `a benign state value still captures - the widened scan is not a blanket drop`() {
        busAcceptsOffer()
        val benign = UiNode(children = listOf(UiNode(text = "Dash now", stateDescription = "Not selected")))
        writer().captureScreen(obs(null, UNKNOWN_TARGET), screenEvent(benign))

        verify(captureBus, times(1)).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertEquals(0L, stats.scrubbedUnknownCaptureCount)
        assertEquals(0L, stats.unknownCustomerScrubCount)
        assertTrue("benign state text survives for triage", capturedEnvelope().contains("Not selected"))
    }

    private fun busAcceptsOffer() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull()))
            .thenReturn("cap-1")
    }
}
