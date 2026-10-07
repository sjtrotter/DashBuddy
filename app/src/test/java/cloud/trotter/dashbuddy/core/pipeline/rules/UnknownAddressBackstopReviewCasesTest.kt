package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.core.pipeline.CaptureWriter
import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.PlatformAppVersions
import cloud.trotter.dashbuddy.core.pipeline.UnknownAddressBackstop
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * #1116 — the counterexample trees of the closed PR #1265's three review rounds, re-asked of the
 * address-block backstop with the PRODUCTION classifier and capture writer. That PR widened a
 * recognition rule; this one changes none, so:
 *  - every frame a neighbouring rule owned on master is still owned by it (picker, going-to-store,
 *    workflow sheet, the timeline combined frame) and keeps its own redact decisions — the backstop never
 *    runs on a recognized frame, so a recognized-frame defect is NOT claimed fixed here;
 *  - every sheet variant that fell UNKNOWN on master (incidental `Pick up by …`, a stray id, an
 *    id-bearing row, no task header) now persists with its street, city/ZIP, note and code masked;
 *  - bounded id-borne values on an UNKNOWN frame (`Return 4417`, a `4417` subpremise) stay plain.
 * Every value is invented.
 */
class UnknownAddressBackstopReviewCasesTest {

    private val ruleset = TestRulesetFactory.screenRuleset
    private val pkg = "com.doordash.driverapp"

    private val classifier = ObservationClassifier(
        mock<JsonRuleInterpreter> { on { screenRuleset } doReturn ruleset },
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
        PlatformAppVersions.NONE,
    )

    private val rulesetRedaction = object : ScreenRedactionSource {
        override fun redactFor(ruleId: String): CompiledRedact? = ruleset.ruleById(ruleId)?.redact
    }

    private class RecordingBus : CaptureBus {
        var envelope: String? = null
        override fun offer(
            captureId: String,
            source: String,
            classification: String?,
            platform: String,
            envelopeJson: String,
            contentHash: Int?,
        ): String? {
            envelope = envelopeJson
            return captureId
        }
    }

    private fun id(suffix: String) = "$pkg:id/$suffix"

    private fun text(value: String, idSuffix: String? = null) =
        UiNode(text = value, viewIdResourceName = idSuffix?.let { id(it) }, className = "android.widget.TextView")

    /** The fielded shape: everything id-less below `android:id/content`. */
    private fun frame(vararg body: UiNode): UiNode = UiNode(
        className = "android.widget.FrameLayout",
        children = listOf(
            UiNode(
                viewIdResourceName = "android:id/content",
                className = "android.widget.FrameLayout",
                children = listOf(
                    UiNode(contentDescription = "Close sheet", className = "android.view.View", isClickable = true),
                    UiNode(className = "android.view.View", children = body.toList()),
                ),
            ),
        ),
    ).restoreParents()

    private fun addressBlock() = UiNode(
        className = "android.view.View",
        children = listOf(text("1234 Sample Ridge Dr"), text("San Antonio, TX 78200")),
    )

    private fun sheetBody(vararg extra: UiNode) = arrayOf(
        text("Deliver to Avery K"),
        addressBlock(),
        text("Leave it at my door"),
        text("\"Leave it on the sample porch\""),
        text("000"),
        *extra,
    )

    private fun event(tree: UiNode) = PipelineEvent.Screen(
        timestamp = 1_000L,
        tree = tree,
        snapshot = TreeSnapshot(tree, packageName = pkg),
        packageName = pkg,
    )

    /** The classification target and the envelope JSON the production writer would persist. */
    private fun persist(tree: UiNode): Pair<String?, String> {
        val obs = classifier.classify(event(tree))
        val bus = RecordingBus()
        CaptureWriter(bus, PipelineStats(), rulesetRedaction).captureScreen(obs, event(tree))
        return obs.target to bus.envelope!!
    }

    private fun assertSheetSecretsMasked(what: String, json: String) {
        assertFalse("$what: name must not persist", json.contains("Avery K"))
        assertFalse("$what: street must not persist", json.contains("Sample Ridge"))
        assertFalse("$what: ZIP must not persist", json.contains("78200"))
        assertFalse("$what: note must not persist", json.contains("sample porch"))
        assertFalse("$what: code must not persist", Regex(""""text"\s*:\s*"000"""").containsMatchIn(json))
        assertTrue("$what: the dropoff option is chrome", json.contains("Leave it at my door"))
        assertTrue("$what: Close sheet is chrome", json.contains("Close sheet"))
    }

    @Test
    fun `neighbouring sheets keep their master winner (r1 P1)`() {
        val shared = arrayOf(text("Pickup for Avery K"), text("Leave it at my door"))
        val owners = mapOf(
            "doordash.screen.pickup_order_picker" to frame(
                *shared, text("Select an Order", "header"), text("For Jordan T by 12:28", "instructions"),
            ),
            "doordash.screen.pickup_going_to_store_sheet" to frame(
                *shared,
                text("Sample instructions", "address_instructions_view"),
                text("Order includes", "lblOrderIncludes"),
                UiNode(viewIdResourceName = id("going_to_store_map")),
            ),
            "doordash.screen.dropoff_workflow_sheet" to frame(
                *shared,
                UiNode(
                    viewIdResourceName = id("drop_off_workflow_host_fragment"),
                    children = listOf(text("Continue", "textView_prism_button_title"), addressBlock()),
                ),
            ),
        )
        for ((owner, tree) in owners) {
            assertEquals("$owner still owns its frame", owner, ruleset.matchFirst(tree)?.ruleId)
        }
        val (_, pickerJson) = persist(owners.getValue("doordash.screen.pickup_order_picker"))
        assertFalse("the picker's own mask still covers the instructions line", pickerJson.contains("Jordan T"))
    }

    @Test
    fun `the timeline combined frame keeps its winner, task prefixes and distinct customer hashes (r3)`() {
        val tree = frame(
            text("Current dash"),
            text("Current task"),
            text("Deliver to Avery K", "customer_name"),
            text("Deliver to Jordan T", "customer_name"),
        )
        val (target, json) = persist(tree)
        assertNotEquals("the combined frame is recognized", UNKNOWN_TARGET, target)
        assertEquals("doordash.screen.timeline", ruleset.matchFirst(tree)?.ruleId)
        val masks = Regex("""Deliver to \[redacted:([0-9a-f]{4})]""").findAll(json).map { it.groupValues[1] }.toList()
        assertEquals("both task lines keep their prefix", 2, masks.size)
        assertEquals("two customers stay distinct", 2, masks.toSet().size)
    }

    @Test
    fun `sheet variants that fall UNKNOWN persist with the address block masked (r2 P1)`() {
        val variants = mapOf(
            "plain sheet" to frame(*sheetBody()),
            "pick-up-by line" to frame(*sheetBody(text("Pick up by 12:28 PM"))),
            "stray id" to frame(*sheetBody(UiNode(viewIdResourceName = id("some_new_container")))),
            "id on the option row" to frame(*sheetBody(text("Order includes", "unrelated_label"))),
            "no task header" to frame(addressBlock(), text("Leave it at my door"), text("\"Leave it on the sample porch\""), text("000")),
        )
        for ((what, tree) in variants) {
            val (target, json) = persist(tree)
            assertEquals("$what: UNKNOWN on master's rules", UNKNOWN_TARGET, target)
            assertSheetSecretsMasked(what, json)
        }
    }

    @Test
    fun `id-bearing UNKNOWN variants mask the id-borne value and the id-less block (r1 P1)`() {
        for (suffix in listOf(
            "customer_name",
            "address_subpremise_line",
            "dasher_instruction_content_collapsed",
            "description_text_view",
        )) {
            val (target, json) = persist(frame(*sheetBody(text("Jordan T 4417", suffix))))
            assertEquals("$suffix: UNKNOWN on master's rules", UNKNOWN_TARGET, target)
            assertFalse("$suffix: id-borne value must not persist", json.contains("Jordan T"))
            assertSheetSecretsMasked(suffix, json)
        }
    }

    private fun scrubbedUnknown(tree: UiNode): UiNode {
        assertEquals("UNKNOWN on master's rules", null, ruleset.matchFirst(tree)?.ruleId)
        return CustomerTextMarkers.scrubUnknown(tree, UnknownAddressBackstop.select(tree))
    }

    @Test
    fun `bounded id-borne values stay plain on an UNKNOWN frame (r2 P4, P5)`() {
        for ((suffix, value) in listOf(
            "dasher_instruction_content_collapsed" to "Return 4417",
            "description_text_view" to "Return 4417",
            "tvLastMessage" to "Return 4417",
            "address_subpremise_line" to "4417",
        )) {
            val out = scrubbedUnknown(frame(*sheetBody(text(value, suffix))))
            val node = out.findNode { it.viewIdResourceName == id(suffix) }!!
            assertEquals("$suffix: plain, never hashed", "[redacted]", node.text)
        }
    }

    @Test
    fun `a bare or quoted code directly above a city line plain-masks on an UNKNOWN frame (r1 P3)`() {
        for (secret in listOf("4417", "\"4417\"")) {
            val tree = frame(text("Order details"), UiNode(children = listOf(text(secret), text("San Antonio, TX 78200"))))
            val block = scrubbedUnknown(tree).children[0].children[1].children[1]
            assertEquals("'$secret' plain-masks", "[redacted]", block.children[0].text)
            assertEquals("the city line plain-masks", "[redacted]", block.children[1].text)
        }
    }

    @Test
    fun `an UNKNOWN return sheet masks the name and keeps the store (r1 P2, r2 P3)`() {
        for (line in listOf("Return Avery K to Sample Store", "Return\tAvery K to Sample Store")) {
            val tree = frame(text(line), addressBlock(), text("Leave it at my door"), text("000"))
            val (target, json) = persist(tree)
            assertEquals("UNKNOWN on master's rules", UNKNOWN_TARGET, target)
            assertFalse("name must not persist", json.contains("Avery K"))
            assertTrue("the store stays raw (#886)", json.contains("[redacted] to Sample Store"))
            assertFalse("street must not persist", json.contains("Sample Ridge"))
        }
    }
}
