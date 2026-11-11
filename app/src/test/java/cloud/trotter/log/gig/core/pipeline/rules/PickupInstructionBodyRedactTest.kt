package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.core.pipeline.CaptureWriter
import cloud.trotter.log.gig.core.pipeline.CustomerTextMarkers
import cloud.trotter.log.gig.core.pipeline.PipelineEvent
import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.log.gig.domain.capture.CaptureBus
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.test.util.DoorDashRuleSource
import cloud.trotter.log.gig.test.util.TestResourceLoader
import cloud.trotter.log.gig.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1397 — DoorDash renders the merchant's pickup instructions on the pickup sheet in
 * `instructions_line_2_expanded` (and its `_collapsed` twin). The text is the merchant's own free text; a
 * fielded one named the customer and a locker code. Every pickup-section screen rule (plus
 * `waiting_for_offer`, which can win a frame under the pickup sheet, and the recognize-only belt rules)
 * masks the node whole and plain, first in its `redact` block; `CustomerTextMarkers.ID_MARKERS` covers an
 * UNKNOWN frame. `instructions_title` (the merchant name `pickup_arrival` parses) stays raw.
 *
 * Trees are committed fixtures with invented values injected ("Jane D.", code 123).
 */
class PickupInstructionBodyRedactTest {

    private val ruleset = TestRulesetFactory.screenRuleset

    private val body = "Look for Jane D. on the Pizza Portal, use code 123"

    private val bodySuffixes = listOf("instructions_line_2_expanded", "instructions_line_2_collapsed")

    private val beltRuleIds = listOf(
        "doordash.screen.waiting_for_offer",
        "doordash.screen.dropoff_customer_unavailable",
        "doordash.screen.dropoff_alcohol_warning_sheet",
    )

    private fun carrierRuleIds(): List<String> = DoorDashRuleSource.screenRuleIds("pickup") + beltRuleIds

    private val screens: Map<String, JsonObject> by lazy {
        Json.parseToJsonElement(File(TestRulesetFactory.rulesDir, "doordash.json").readText())
            .jsonObject.getValue("screens").jsonArray.map { it.jsonObject }
            .associateBy { it.getValue("id").jsonPrimitive.content }
    }

    private class RecordingBus : CaptureBus {
        val envelopes = mutableListOf<String>()
        override fun offer(
            captureId: String, source: String, classification: String?, platform: String,
            envelopeJson: String, contentHash: Int?,
        ): String? { envelopes += envelopeJson; return captureId }
    }

    private fun UiNode.idEndsWith(suffix: String) = viewIdResourceName?.endsWith(suffix) == true

    private fun UiNode.rewrite(edit: (UiNode) -> UiNode): UiNode = edit(copy(children = children.map { it.rewrite(edit) }))

    /** Every instruction body set to [text], and a collapsed twin added beside each expanded container. */
    private fun withInjectedBody(tree: UiNode, text: String): UiNode = tree.rewrite { n ->
        when {
            n.idEndsWith("instructions_line_2_expanded") || n.idEndsWith("instructions_line_2_collapsed") -> n.copy(text = text)
            n.children.any { it.idEndsWith("instructions_expanded_container") } -> n.copy(
                children = n.children + UiNode(
                    className = "android.widget.TextView",
                    viewIdResourceName = "com.doordash.driverapp:id/instructions_line_2_collapsed",
                    text = text,
                ),
            )
            else -> n
        }
    }.restoreParents()

    /** (view id suffix, text) of every node in an envelope payload. */
    private fun payloadNodes(envelope: String): List<Pair<String, String?>> {
        val out = mutableListOf<Pair<String, String?>>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    val id = (e["id"] as? JsonPrimitive)?.contentOrNull
                    if (id != null) out += id.substringAfterLast('/') to (e["text"] as? JsonPrimitive)?.contentOrNull
                    e.values.forEach(::walk)
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(Json.parseToJsonElement(envelope).jsonObject.getValue("payload"))
        return out
    }

    private fun texts(tree: UiNode, suffix: String): List<String?> {
        val out = mutableListOf<String?>()
        fun walk(n: UiNode) {
            if (n.viewIdResourceName?.substringAfterLast('/') == suffix) out += n.text
            n.children.forEach(::walk)
        }
        walk(tree)
        return out
    }

    @Test
    fun `a recognized at-store card ships the instruction body masked, the merchant name raw (#1397)`() {
        val fixture = TestResourceLoader.loadNode(
            File("src/test/resources/snapshots/pickup_arrival/20260128_194846_850_PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE.json"),
        )
        val tree = withInjectedBody(fixture, body)
        assertTrue("the expanded body was injected", texts(tree, "instructions_line_2_expanded").contains(body))
        assertTrue("the collapsed twin was injected", texts(tree, "instructions_line_2_collapsed").contains(body))
        val match = ruleset.matchFirst(tree, platformWire = "doordash")
        assertEquals("doordash.screen.pickup_arrival", match?.ruleId)

        val bus = RecordingBus()
        val writer = CaptureWriter(bus, PipelineStats(), object : ScreenRedactionSource {
            override fun redactFor(ruleId: String): CompiledRedact? = ruleset.ruleById(ruleId)?.redact
        })
        writer.captureScreen(
            Observation.Screen(
                timestamp = 1_000L, captureId = null, ruleId = match!!.ruleId, metadata = ReplayMetadata.EMPTY,
                flow = null, modeHint = null, parsed = ParsedFields.None, target = match.intent,
            ),
            PipelineEvent.Screen(
                timestamp = 1_000L, tree = tree,
                snapshot = TreeSnapshot(tree = tree, packageName = "com.doordash.driverapp"),
                packageName = "com.doordash.driverapp",
            ),
        )
        val envelope = bus.envelopes.single()
        assertFalse(envelope.contains("Jane"))
        assertFalse(envelope.contains("Pizza Portal"))
        val nodes = payloadNodes(envelope)
        for (suffix in bodySuffixes) {
            val values = nodes.filter { it.first == suffix }.map { it.second }
            assertTrue("$suffix must be present in the envelope", values.isNotEmpty())
            assertTrue("$suffix must read [redacted]: $values", values.all { it == "[redacted]" })
        }
        assertEquals(
            "instructions_title is the merchant and stays raw",
            texts(fixture, "instructions_title"),
            nodes.filter { it.first == "instructions_title" }.map { it.second },
        )
    }

    @Test
    fun `every committed pickup frame carrying an instruction body masks it under its winning rule (#1397)`() {
        var checked = 0
        for (folder in listOf("pickup_arrival", "pickup_pre_arrival", "pickup_wait_survey", "pickup_steps")) {
            for ((name, fixture, _) in TestResourceLoader.loadSnapshots("snapshots/$folder")) {
                if (bodySuffixes.none { texts(fixture, it).isNotEmpty() }) continue
                val tree = withInjectedBody(fixture, body)
                val match = ruleset.matchFirst(tree, platformWire = "doordash")
                assertTrue("$folder/$name must stay recognized (${match?.ruleId})", match != null)
                val masked = ruleset.ruleById(match!!.ruleId)!!.redact.apply(tree)
                for (suffix in bodySuffixes) {
                    assertTrue("$folder/$name: $suffix masked by ${match.ruleId}", texts(masked, suffix).all { it == "[redacted]" })
                }
                assertEquals("$folder/$name: instructions_title untouched", texts(tree, "instructions_title"), texts(masked, "instructions_title"))
                checked++
            }
        }
        assertTrue("the sweep must reach the committed carriers (found $checked)", checked >= 20)
    }

    @Test
    fun `every pickup-section rule and the belt rules mask both body ids first and plain (#1397)`() {
        val problems = mutableListOf<String>()
        for (ruleId in carrierRuleIds()) {
            val rule = screens[ruleId]
            if (rule == null) {
                problems += "$ruleId: not in the generated asset"
                continue
            }
            val first = rule["redact"]?.jsonArray?.firstOrNull()?.jsonObject
            if (first == null) {
                problems += "$ruleId: no redact block"
                continue
            }
            val anyOf = first["find"]?.jsonObject?.get("any")?.jsonArray
                ?.mapNotNull { it.jsonObject["hasIdSuffix"]?.jsonPrimitive?.contentOrNull }
            if (anyOf?.toSet() != bodySuffixes.toSet() || first["find"]!!.jsonObject.size != 1) {
                problems += "$ruleId: first redact entry is not the instruction-body pair (${first["find"]})"
            }
            if (first["plainMask"]?.jsonPrimitive?.booleanOrNull != true) problems += "$ruleId: entry is not plainMask"
            if (first.containsKey("normalize") || first.containsKey("keepPrefix")) problems += "$ruleId: entry must mask the whole node"
        }
        assertTrue("instruction-body redact drift: $problems", problems.isEmpty())
    }

    @Test
    fun `each carrier rule's compiled redact masks both ids whole (#1397)`() {
        for (ruleId in carrierRuleIds()) {
            val redact = ruleset.ruleById(ruleId)!!.redact
            for (suffix in bodySuffixes) {
                val node = UiNode(viewIdResourceName = "com.doordash.driverapp:id/$suffix", text = body)
                assertEquals("$ruleId / $suffix", "[redacted]", redact.apply(UiNode(children = listOf(node)).restoreParents()).children.single().text)
            }
        }
    }

    @Test
    fun `the body ids are in the UNKNOWN-path id SSOT and instructions_title is not (#1397)`() {
        for (suffix in bodySuffixes) assertTrue(suffix, suffix in CustomerTextMarkers.ID_MARKERS)
        assertFalse(CustomerTextMarkers.ID_MARKERS.any { "instructions_title".endsWith(it) })
    }
}
