package cloud.trotter.dashbuddy.census

import cloud.trotter.census.contract.authoring.BindAssignment
import cloud.trotter.census.contract.authoring.Constant
import cloud.trotter.census.contract.authoring.DraftResult
import cloud.trotter.census.contract.authoring.EnvelopeWalk
import cloud.trotter.census.contract.authoring.FieldAssignment
import cloud.trotter.census.contract.authoring.NodeRef
import cloud.trotter.census.contract.authoring.RuleDraft
import cloud.trotter.census.contract.authoring.Selections
import cloud.trotter.census.contract.authoring.at
import cloud.trotter.dashbuddy.core.pipeline.recognition.matchers.NegativeCorpusStaysUnknownTest
import cloud.trotter.dashbuddy.core.pipeline.rules.ParsedFieldsFactory
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleCompiler
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleContext
import cloud.trotter.dashbuddy.core.pipeline.rules.Ruleset
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Known-capture authoring gate (#1188). All paths are child indices from payload root; the tests
 * additionally pin the view id at each selected path so a fixture edit cannot silently retarget it.
 *
 * Offer (2026-08-28 15:31:47): O = [0,0,0,0,0,0,0]. Anchors O+[3,0,0] accept_button
 * and O+[0] accept_constraint_layout identify the legacy card chrome without quoting dynamic text.
 * Binds use that accept node and O+[0,1] secondary_action_button_dash_plus (both clickable).
 * Fields share prefix O+[1,2,0,0,0,0,0]: [1,0,0] payAmount, [2,0,0] distance (3.8 mi),
 * [3,0,0] deliveryTimeText (Deliver by 3:49 PM, using stripDeadlinePrefix). All share id
 * text_field and class TextView; distance and deadline now disambiguate by full value shape.
 * Pay is actually "$8.30  Guaranteed (incl. tips)": the specified anchored currency shape DOES
 * NOT match, so this gate still asserts exactly "ambiguous id for field" at the pay path.
 * No constant or altered fixture substitutes for the failed read; the currency shape stays strict.
 *
 * Dropoff (2026-08-02 18:00:01): D = [0,0,0,0,0,1,0,3,1,1,0,2,0]. Anchors D
 * bottom_sheet_in_transit_navigation_container and D+[7] bottom_sheet_instructions_title combine
 * navigation chrome with customer-handoff chrome. Fields D+[2] bottom_sheet_task_title and D+[1]
 * bottom_sheet_task_arrive_by are unique ids, keeping the name and deadline OUT of rule literals.
 * The sanitized name slot uses "Deliver to " (not the longer shipped "Deliver to door of " form).
 * stripPrefix removes that actual chrome prefix before canonical name normalization and hashing;
 * keepPrefix preserves the label in the matching redact entry.
 *
 * Expanded summary (2026-09-07 08:20:08): S = [0,0,0,0,0]. Anchors S layout_bottom_sheet
 * and S+[2,1,0,0,0] bottomsheet_content_container are the available sheet ids. totalPay at
 * S+[2,1,0,0,0,0,0,2,0,0,2] is $40.57, id-less, and follows a desc-only Collapse node.
 * That label at the same path ending in [1] is a third anchor: the two generic sheet ids alone
 * also match a negative frame, so the label narrows recognition to the expanded receipt.
 * Its preceding sibling has NO text; siblingOf the unique Collapse desc reads the total at offset 1.
 * The clickable preceding control is Collapse, not Expand; no expand binding.
 * No constant substitutes for a failed value read; isExpanded=true is the only operator constant.
 */
class CensusDraftRoundTripTest {
    @Test
    fun `offer still refuses pay with trailing chrome while distance and deadline disambiguate`() {
        val envelope = fixture("offer_popup", "2026-08-28_15-31-47-225")
        val chrome = listOf(0, 0, 0, 0, 0, 0, 0)
        val rows = chrome + listOf(1, 2, 0, 0, 0, 0, 0)
        val accept = ref(envelope, chrome + listOf(3, 0, 0), "accept_button")
        val pay = ref(envelope, rows + listOf(1, 0, 0), "text_field")
        val distance = ref(envelope, rows + listOf(2, 0, 0), "text_field")
        val deadline = ref(envelope, rows + listOf(3, 0, 0), "text_field")
        val selection = Selections(
            "offer:presented", "offer", "drafted_offer_popup", 50, offerSurface = "card",
            anchors = listOf(accept, ref(envelope, chrome + 0, "accept_constraint_layout")),
            fields = listOf(FieldAssignment(pay, "payAmount"), FieldAssignment(distance, "distance"),
                FieldAssignment(deadline, "deliveryTimeText", listOf("stripDeadlinePrefix"))),
            binds = listOf(BindAssignment(accept, "acceptButton"), BindAssignment(
                ref(envelope, chrome + listOf(0, 1), "secondary_action_button_dash_plus"), "declineButton")),
        )
        assertEquals(DraftResult.Refused(listOf("ambiguous id for field at ${pay.path}")), generate(envelope, selection))
    }

    @Test
    fun `dropoff compiles alone recognises source stays unknown on negatives and compiles merged`() {
        val envelope = fixture("dropoff_navigation", "2026-08-02_18-00-01-999")
        val content = listOf(0, 0, 0, 0, 0, 1, 0, 3, 1, 1, 0, 2, 0)
        val selection = Selections(
            "task:dropoff:navigation", "task", "drafted_dropoff_navigation", 50,
            anchors = listOf(ref(envelope, content, "bottom_sheet_in_transit_navigation_container"),
                ref(envelope, content + 7, "bottom_sheet_instructions_title")),
            fields = listOf(
                FieldAssignment(ref(envelope, content + 2, "bottom_sheet_task_title"), "customerNameHash", stripPrefix = "Deliver to "),
                FieldAssignment(ref(envelope, content + 1, "bottom_sheet_task_arrive_by"), "deadlineText"),
            ),
        )
        val fields = verifyRoundTrip(envelope, selection) as ParsedFields.TaskFields
        assertEquals(TaskPhase.DROPOFF, fields.phase)
        assertEquals(TaskSubFlow.NAVIGATION, fields.subFlow)
        assertEquals("6:02 PM", fields.deadline?.text)
        val assignment = selection.fields.first()
        val rawName = requireNotNull(EnvelopeWalk.walk(envelope.getValue("payload").jsonObject)
            .at(assignment.node.path)?.text)
        val bareName = rawName.removePrefix(requireNotNull(assignment.stripPrefix)).trim()
        // Same canonicalization as CustomerNameKey.kt (normalizeCustomerName): ROOT lowercase,
        // letters/digits/whitespace only, then first token plus second token's initial.
        val tokens = bareName.lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
        assertTrue("fixture must contain a bare name", tokens.isNotEmpty())
        val normalized = tokens.first() + tokens.getOrNull(1)?.let { " ${it.first()}" }.orEmpty()
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertTrue("customer hash must use the normalized bare name", expectedHash == fields.customerNameHash)
        val rule = assertOk(generate(envelope, selection)).fragment.getValue("screens").jsonArray.single().jsonObject
        val redact = rule.getValue("redact").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("customerName"), redact["normalize"])
        assertEquals(JsonArray(listOf(JsonPrimitive("Deliver to "))), redact["keepPrefix"])
        assertEquals(rule.getValue("parse").jsonObject.getValue("fields").jsonObject
            .getValue("customerNameHash").jsonObject["find"], redact["find"])
    }

    private fun verifyRoundTrip(envelope: JsonObject, selection: Selections): ParsedFields {
        val draft = assertOk(generate(envelope, selection))
        assertNoCaptureIdentifiers(envelope, draft)
        println(draft.json5) // Exactly once, after the provenance/privacy assertion.
        val rule = draft.fragment.getValue("screens").jsonArray.single().jsonObject
        val ruleset = Ruleset(RuleCompiler.compileRules<UiNode>(JsonArray(listOf(rule)), RuleContext.SCREEN))
        val source = TestResourceLoader.nodeFromElement(envelope.getValue("payload"))
        val match = requireNotNull(ruleset.matchFirst(source, platformWire = "doordash")) { "source frame must match" }
        assertEquals("doordash.screen.${selection.intent}", match.ruleId)
        val negatives = TestResourceLoader.loadSnapshots(NegativeCorpusStaysUnknownTest.FOLDER)
        assertTrue("negative corpus floor", negatives.size >= NegativeCorpusStaysUnknownTest.MINIMUM_FRAMES)
        val platformNegatives = negatives.filter {
            NegativeCorpusStaysUnknownTest.platformWireOf(it.first) == "doordash"
        }
        assertTrue("DoorDash negative corpus floor", platformNegatives.size >= MINIMUM_FRAMES)
        platformNegatives.forEachIndexed { index, (_, node, _) ->
            // Do not print capture-bearing filenames, including on a failed assertion.
            assertNull("DoorDash negative frame $index must remain UNKNOWN", ruleset.matchFirst(node, platformWire = "doordash"))
        }

        val production = Json.parseToJsonElement(File(TestRulesetFactory.rulesDir, "doordash.json").readText())
            .jsonObject.getValue("screens").jsonArray
        val usedPriorities = production.map { it.jsonObject.getValue("priority").jsonPrimitive.int }.toSet()
        val freePriority = (1..998).first { it !in usedPriorities }
        val mergedDraft = assertOk(generate(envelope, selection.copy(priority = freePriority)))
        val mergedRule = mergedDraft.fragment.getValue("screens").jsonArray.single()
        val compiled = RuleCompiler.compileRules<UiNode>(JsonArray(production + mergedRule), RuleContext.SCREEN)
        assertTrue("merged compiler must retain the draft", compiled.any { it.id == match.ruleId })
        assertEquals(production.size + 1, compiled.size)
        return ParsedFieldsFactory.create(match.shape, match.fields)
    }

    @Test
    fun `expanded summary reads total via desc sibling and passes the round trip`() {
        val envelope = fixture("delivery_summary_expanded", "2026-09-07_08-20-08-176")
        val sheet = listOf(0, 0, 0, 0, 0)
        val total = NodeRef(sheet + listOf(2, 1, 0, 0, 0, 0, 0, 2, 0, 0, 2))
        val walked = EnvelopeWalk.walk(envelope.getValue("payload").jsonObject)
        val totalNode = requireNotNull(walked.at(total.path))
        assertEquals("$40.57", totalNode.text)
        assertNull(totalNode.idSuffix)
        assertNull(totalNode.precedingSiblingText)
        assertEquals("Collapse", totalNode.precedingSiblingDesc)
        val selection = Selections(
            "post:task", "post_task", "drafted_delivery_summary_expanded", 50,
            anchors = listOf(ref(envelope, sheet, "layout_bottom_sheet"),
                ref(envelope, sheet + listOf(2, 1, 0, 0, 0), "bottomsheet_content_container"),
                NodeRef(total.path.dropLast(1) + 1)),
            fields = listOf(FieldAssignment(total, "totalPay")),
            constants = listOf(Constant("isExpanded", JsonPrimitive(true))),
        )
        val fields = verifyRoundTrip(envelope, selection) as ParsedFields.PostTaskFields
        assertEquals(40.57, fields.totalPay, 0.000001)
        assertTrue(fields.isExpanded)
        val rule = assertOk(generate(envelope, selection)).fragment.getValue("screens").jsonArray.single().jsonObject
        val totalExpression = rule.getValue("parse").jsonObject.getValue("fields").jsonObject.getValue("totalPay").jsonObject
        assertEquals(Json.parseToJsonElement("""{"hasDesc":"Collapse"}"""), totalExpression["siblingOf"])
        assertFalse("find" in totalExpression)
    }

    private fun fixture(folder: String, prefix: String): JsonObject {
        val directory = File("src/test/resources/snapshots/$folder")
        val file = directory.listFiles().orEmpty().single { it.name.startsWith(prefix) && it.extension == "json" }
        val envelope = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("uinode.v1", envelope.getValue("schemaId").jsonPrimitive.content)
        return envelope
    }

    private fun ref(envelope: JsonObject, path: List<Int>, id: String): NodeRef {
        val node = requireNotNull(EnvelopeWalk.walk(envelope.getValue("payload").jsonObject).at(path))
        assertEquals("view id at $path", id, node.idSuffix)
        return NodeRef(path)
    }

    private fun generate(envelope: JsonObject, selection: Selections): DraftResult =
        RuleDraft.generate(envelope, selection, "doordash", null, "2026-10-04")

    private fun assertOk(result: DraftResult): DraftResult.Ok {
        assertTrue("draft should be accepted: $result", result is DraftResult.Ok)
        return result as DraftResult.Ok
    }

    private fun assertNoCaptureIdentifiers(envelope: JsonObject, draft: DraftResult.Ok) {
        assertFalse(draft.json5.contains("captureId", ignoreCase = true))
        assertFalse(draft.json5.contains("fingerprint", ignoreCase = true))
        assertFalse(Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").containsMatchIn(draft.json5))
        val metadata = envelope["metadata"] as? JsonObject
        val identifiers = listOf(envelope["captureId"], envelope["fingerprint"], metadata?.get("deviceFingerprint"))
        for (identifier in identifiers) {
            val value = (identifier as? JsonPrimitive)?.content ?: continue
            if (value.isNotBlank()) assertFalse("capture metadata must not enter the draft", draft.json5.contains(value))
        }
    }

    private companion object {
        // The shared corpus floor is ten; seven of its current frames carry the DoorDash token.
        const val MINIMUM_FRAMES = 7
    }
}
