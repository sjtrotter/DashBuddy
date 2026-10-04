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
import cloud.trotter.dashbuddy.domain.privacy.PiiShapes
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * text_field and class TextView; each field disambiguates among same-id peers by value shape.
 * Pay is "$8.30  Guaranteed (incl. tips)": the anchored currency shape allows trailing chrome,
 * and parseCurrency reads the first amount token. All three selections warn about shape identity.
 *
 * Dropoff (2026-08-02 18:00:01): D = [0,0,0,0,0,1,0,3,1,1,0,2,0]. Anchors D
 * bottom_sheet_in_transit_navigation_container and D+[7] bottom_sheet_instructions_title combine
 * navigation chrome with customer-handoff chrome. Fields D+[2] bottom_sheet_task_title and D+[1]
 * bottom_sheet_task_arrive_by are unique ids, keeping the name and deadline OUT of rule literals.
 * The sanitized name slot uses "Deliver to " (not the longer shipped "Deliver to door of " form).
 * stripPrefix removes that actual chrome prefix before canonical name normalization and hashing;
 * keepPrefix preserves the label in the matching redact entry. The round-3 unanchored name-body
 * check currently refuses this prefix (it matches "Deliver t"); the expected-Ok gate stays loud.
 * Explicit plain-mask redacts cover production's other masked slots: D+[3]/[4] sheet address
 * lines, D+[8] instructions, and [0,0,0,0,0,1,0,2,1] arriving_at_title (arrival address).
 * These ids are selected from the winning production dropoff_navigation redact block.
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
    fun `offer reads qualified pay distance and time and passes the round trip`() {
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
        val fields = verifyRoundTrip(envelope, selection) as ParsedFields.OfferFields
        assertEquals(8.30, requireNotNull(fields.parsedOffer.payAmount), 0.000001)
        assertEquals(3.8, requireNotNull(fields.parsedOffer.distanceMiles), 0.000001)
        val draft = assertOk(generate(envelope, selection))
        val rule = draft.fragment.getValue("screens").jsonArray.single().jsonObject
        assertEquals(Json.parseToJsonElement("""["accept_offer","decline_offer"]"""), rule["enables"])
        assertEquals(setOf("acceptButton", "declineButton"), rule.getValue("bind").jsonObject.keys)
        assertEquals(3, draft.warnings.count { "identity rests on a value shape" in it })
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
            redacts = listOf(
                ref(envelope, content + 3, "bottom_sheet_address_line_1"),
                ref(envelope, content + 4, "bottom_sheet_address_line_2"),
                ref(envelope, content + 8, "bottom_sheet_instructions"),
                ref(envelope, listOf(0, 0, 0, 0, 0, 1, 0, 2, 1), "arriving_at_title"),
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
        val redact = rule.getValue("redact").jsonArray.first().jsonObject
        assertEquals(JsonPrimitive("customerName"), redact["normalize"])
        assertEquals(JsonArray(listOf(JsonPrimitive("Deliver to "))), redact["keepPrefix"])
        assertEquals(rule.getValue("parse").jsonObject.getValue("fields").jsonObject
            .getValue("customerNameHash").jsonObject["find"], redact["find"])
    }

    private fun verifyRoundTrip(envelope: JsonObject, selection: Selections): ParsedFields {
        val draft = assertOk(generate(envelope, selection))
        assertNoCaptureIdentifiers(envelope, draft)
        assertSafeLiterals(draft.fragment)
        assertFragmentFieldShapes(draft.fragment)
        println(draft.json5) // Exactly once, after the provenance/privacy assertion.
        val rule = draft.fragment.getValue("screens").jsonArray.single().jsonObject
        val draftRules = RuleCompiler.compileRules<UiNode>(JsonArray(listOf(rule)), RuleContext.SCREEN)
        val ruleset = Ruleset(draftRules)
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
        val freePriority = (998 downTo 1).first { it !in usedPriorities }
        val productionRules = RuleCompiler.compileRules<UiNode>(production, RuleContext.SCREEN)
        val productionRuleset = Ruleset(productionRules)
        val productionMatch = requireNotNull(productionRuleset.matchFirst(source, platformWire = "doordash"))
        // Exactly CaptureWriter's rule-declared redact step: CompiledRedact.apply on the source tree.
        val productionEnvelope = productionRules.single { it.id == productionMatch.ruleId }.redact.apply(source)
        val draftEnvelope = draftRules.single().redact.apply(source)
        val productionMasks = maskedSlots(productionEnvelope)
        val draftMasks = maskedSlots(draftEnvelope)
        assertTrue("draft must cover production masks at node paths: ${productionMasks - draftMasks}",
            draftMasks.containsAll(productionMasks))
        val mergedDraft = assertOk(generate(envelope, selection.copy(priority = freePriority)))
        val mergedRule = mergedDraft.fragment.getValue("screens").jsonArray.single()
        val compiled = RuleCompiler.compileRules<UiNode>(JsonArray(production + mergedRule), RuleContext.SCREEN)
        assertTrue("merged compiler must retain the draft", compiled.any { it.id == match.ruleId })
        assertEquals(production.size + 1, compiled.size)
        assertEquals("merged draft must not displace production", productionMatch.ruleId,
            Ruleset(compiled).matchFirst(source, platformWire = "doordash")?.ruleId)
        if (selection.shape == "offer") {
            assertEquals("3:49 PM", match.fields["deliveryTimeText"])
            assertEquals(setOf("acceptButton", "declineButton"), match.targets.keys)
        }
        val parsed = ParsedFieldsFactory.create(match.shape, match.fields)
        if (parsed is ParsedFields.PostTaskFields) {
            assertEquals(40.57, parsed.totalPay, 0.000001)
            assertTrue(parsed.isExpanded)
        }
        if (parsed is ParsedFields.OfferFields) {
            assertEquals(8.30, requireNotNull(parsed.parsedOffer.payAmount), 0.000001)
            assertEquals(3.8, requireNotNull(parsed.parsedOffer.distanceMiles), 0.000001)
        }
        println("Gate ${selection.shape}: source, parsed values, negatives, redaction coverage and merged winner checked")
        // The draft merges at the LOWEST free rank so it pre-empts no shipped recognition rule. The one production rule
        // that may rank below it is the overrideable `sensitive.catchall` at 999 — a last-resort banking-term net that
        // specific recognition is MEANT to out-rank (#419); drafts are capped at 998 by the vocabulary.
        val rankedBelowDraft = production.map { it.jsonObject }
            .filter { it.getValue("priority").jsonPrimitive.int > freePriority }
            .map { it.getValue("id").jsonPrimitive.content }
        assertTrue("only the sensitive catchall may rank below the draft, found: $rankedBelowDraft",
            rankedBelowDraft.all { it.endsWith(".sensitive.catchall") })
        return parsed
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

    private fun maskedSlots(root: UiNode): Set<Pair<List<Int>, String>> {
        val slots = mutableSetOf<Pair<List<Int>, String>>()
        fun walk(node: UiNode, path: List<Int>) {
            if (node.text?.contains("[redacted", ignoreCase = true) == true) slots += path to "text"
            if (node.contentDescription?.contains("[redacted", ignoreCase = true) == true) slots += path to "desc"
            node.children.forEachIndexed { index, child -> walk(child, path + index) }
        }
        walk(root, emptyList())
        return slots
    }

    private fun assertSafeLiterals(element: JsonElement) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key in setOf("hasText", "hasDesc", "hasPrecedingSiblingText")) {
                    val text = value.jsonPrimitive.content
                    assertFalse("name pattern must never enter match literals",
                        Regex(PiiShapes.FIRST_LAST_INITIAL_PATTERN, RegexOption.IGNORE_CASE).containsMatchIn(text))
                    assertFalse("customer lead-in must never enter match literals",
                        PiiShapes.NAME_PREFIXES.any { text.trimStart().startsWith(it.trimEnd(), ignoreCase = true) })
                    assertFalse("digits/currency must never enter match literals", Regex("""[\p{Nd}\p{Sc}]""").containsMatchIn(text))
                }
                assertSafeLiterals(value)
            }
            is JsonArray -> element.forEach(::assertSafeLiterals)
            else -> Unit
        }
    }

    private fun assertFragmentFieldShapes(fragment: JsonObject) {
        // Offline fragment-schema scalar union: string, boolean, integer, or expression object.
        val fragmentSchema = Json.parseToJsonElement(File("../docs/rules.fragment.schema.json").readText()).jsonObject
        assertEquals("rules.schema.json#/\$defs/screenRule", fragmentSchema.getValue("properties").jsonObject
            .getValue("screens").jsonObject.getValue("items").jsonObject.getValue("\$ref").jsonPrimitive.content)
        val schema = Json.parseToJsonElement(File("../docs/rules.schema.json").readText()).jsonObject
        val union = schema.getValue("\$defs").jsonObject.getValue("parseExpression").jsonObject.getValue("oneOf").jsonArray
        assertEquals(setOf("string", "boolean", "integer"), union.mapNotNull { it.jsonObject["type"]?.jsonPrimitive?.content }.toSet())
        assertTrue(union.any { it.jsonObject["\$ref"]?.jsonPrimitive?.content == "#/\$defs/parseExpressionObject" })
        for (screen in fragment.getValue("screens").jsonArray) {
            val fields = screen.jsonObject.getValue("parse").jsonObject["fields"]?.jsonObject.orEmpty()
            for ((name, value) in fields) {
                assertTrue("schema parse.fields.$name", value is JsonObject ||
                    (value is JsonPrimitive && value != JsonNull &&
                        (value.isString || value.booleanOrNull != null || value.intOrNull != null)))
            }
        }
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
