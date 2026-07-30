package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedact
import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedactEntry
import cloud.trotter.dashbuddy.core.pipeline.rules.ScreenRedactionSource
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #910 V4 — a click envelope INHERITS the redact of the rule that recognized the
 * screen the tap landed on.
 *
 * The fielded leak: tapping a row of the recognized `pickup_post_arrival_multi` list
 * produced an UNKNOWN click envelope whose payload was the tapped subtree, carrying a
 * bare `customer_name` node RAW — while the screen envelope for the very same surface
 * masked it correctly. `captureClick` consulted no rule at all, and the marker backstop
 * is structurally blind to a bare name node, so nothing covered the click path.
 *
 * The screen's rule is the one authority on which of ITS nodes are PII, so the fix
 * applies that same compiled block to the click payload rather than inventing a second
 * enumeration (principle 5). These tests pin the wiring: applied on BOTH recognized and
 * UNKNOWN clicks, fail-OPEN when there is no screen rule or no redact block, and
 * envelope-only (the original node is never mutated, so the dedup identity upstream —
 * `clickDedupHash(event.node, screenTarget)` — is unaffected).
 */
class ClickEnvelopeScreenRedactTest {

    private val offered = mutableListOf<String>()

    private val bus = object : CaptureBus {
        override fun offer(
            captureId: String,
            source: String,
            classification: String?,
            platform: String,
            envelopeJson: String,
            contentHash: Int?,
        ): String {
            offered += envelopeJson
            return captureId
        }
    }

    /** A stand-in for the live interpreter: one rule id, one compiled `redact` block. */
    private object CustomerNameRedaction : ScreenRedactionSource {
        private val block = CompiledRedact(
            listOf(
                CompiledRedactEntry(
                    find = { node -> node.viewIdResourceName?.endsWith("customer_name") == true },
                ),
            ),
        )

        override fun redactFor(ruleId: String): CompiledRedact? =
            block.takeIf { ruleId == RULE_ID }

        const val RULE_ID = "doordash.screen.pickup_post_arrival_multi"
    }

    private val stats = PipelineStats()
    private val writer = CaptureWriter(bus, stats, CustomerNameRedaction)

    /** The fielded shape: the tapped row subtree, one bare customer name + merchant. */
    private fun row() = UiNode(
        viewIdResourceName = "com.doordash.driverapp:id/pickup_row",
        isClickable = true,
        children = listOf(
            UiNode(viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Testname Q"),
            UiNode(viewIdResourceName = "com.doordash.driverapp:id/merchant_name", text = "Pei Wei"),
        ),
    )

    private fun clickObs(target: String = UNKNOWN_TARGET, ruleId: String? = null) = Observation.Click(
        timestamp = 1_000L,
        captureId = null,
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.None,
        target = target,
    )

    private fun capture(
        node: UiNode,
        target: String = UNKNOWN_TARGET,
        ruleId: String? = null,
        screenRuleId: String?,
    ): String {
        offered.clear()
        writer.captureClick(
            clickObs(target, ruleId),
            PipelineEvent.Click(timestamp = 1_000L, node = node, packageName = "com.doordash.driverapp"),
            screenTarget = "pickup_post_arrival_multi",
            screenRuleId = screenRuleId,
        )
        return offered.single()
    }

    @Test
    fun `an UNKNOWN click on a recognized screen inherits that screen rule's redact`() {
        val json = capture(row(), screenRuleId = CustomerNameRedaction.RULE_ID)

        assertFalse("the tapped row's customer name must not persist", json.contains("Testname Q"))
        assertTrue("masked to the hash family", Regex("""\[redacted:[0-9a-f]{4}]""").containsMatchIn(json))
        assertTrue("merchant kept — the rule masks only what it declares", json.contains("Pei Wei"))
        // The rule-declared mask is not a backstop scrub: the backstop counter stays 0.
        assertEquals(0L, stats.unknownCustomerScrubCount)
    }

    @Test
    fun `a RECOGNIZED click on a recognized screen inherits it too`() {
        // A rule-matched click is usually an app-vocabulary button, but the fielded
        // surface proves a tapped node can be a customer row — so the inheritance is
        // not gated on the click's own classification.
        val json = capture(
            row(),
            target = "pickup_row_tap",
            ruleId = "doordash.click.pickup_row",
            screenRuleId = CustomerNameRedaction.RULE_ID,
        )

        assertFalse("customer name must not persist", json.contains("Testname Q"))
        assertTrue("merchant kept", json.contains("Pei Wei"))
    }

    @Test
    fun `no screen rule means no inheritance - fail OPEN, byte-identical to pre-910`() {
        // A benign node under an unattributable screen: nothing to inherit, nothing masked.
        val benign = UiNode(viewIdResourceName = "com.doordash.driverapp:id/some_button", text = "Continue")
        val json = capture(benign, screenRuleId = null)

        assertTrue("node persists untouched", json.contains("Continue"))
        assertFalse("nothing masked", json.contains("[redacted"))
    }

    @Test
    fun `a screen rule with no redact block leaves the node untouched`() {
        val json = capture(
            UiNode(viewIdResourceName = "com.doordash.driverapp:id/some_button", text = "Continue"),
            screenRuleId = "doordash.screen.some_other_rule",
        )

        assertTrue("node persists untouched", json.contains("Continue"))
    }

    @Test
    fun `the redact is envelope-only - the original node is never mutated`() {
        // The dedup identity (clickDedupHash) and every downstream consumer read the
        // ORIGINAL node; masking a copy is what keeps that hash stable across the fix.
        val original = row()
        capture(original, screenRuleId = CustomerNameRedaction.RULE_ID)

        assertEquals("Testname Q", original.children[0].text)
    }
}
