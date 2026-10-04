/*
 * Copyright 2026 Stephen Trotter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package cloud.trotter.dashbuddy.census

import cloud.trotter.census.contract.authoring.BindAssignment
import cloud.trotter.census.contract.authoring.Constant
import cloud.trotter.census.contract.authoring.DraftResult
import cloud.trotter.census.contract.authoring.EnvelopeWalk
import cloud.trotter.census.contract.authoring.FieldAssignment
import cloud.trotter.census.contract.authoring.PathRef
import cloud.trotter.census.contract.authoring.RuleDraft
import cloud.trotter.census.contract.authoring.Selections
import cloud.trotter.census.contract.authoring.at
import cloud.trotter.dashbuddy.core.pipeline.recognition.matchers.NegativeCorpusStaysUnknownTest
import cloud.trotter.dashbuddy.core.pipeline.rules.RedactNormalize
import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedact
import cloud.trotter.dashbuddy.core.pipeline.rules.ParsedFieldsFactory
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleCompiler
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleContext
import cloud.trotter.dashbuddy.core.pipeline.rules.Ruleset
import cloud.trotter.dashbuddy.core.pipeline.rules.TransformRegistry
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
 * keepPrefix preserves the label in the matching redact entry. Approved lowercase chrome is accepted.
 * Explicit plain-mask redacts cover production's other populated masked slots: D+[3] sheet address,
 * D+[8] instructions, and [0,0,0,0,0,1,0,2,1] arriving_at_title (arrival address).
 * D+[4] address line two is empty and is omitted: each declared redact must protect plaintext here.
 * Coverage restores corpus mask tokens to synthetic plaintext before constructing its UiNode,
 * compares remaining plaintext tokens per slot as well as mask paths, and must fail when ANY
 * dropoff redact (including the automatic name entry) is regenerated away.
 * All three screens draft; merged rank may outrank only the shipped sensitive catchall at 999.
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
        val envelope = fixture("offer_popup")
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
        val envelope = fixture("dropoff_navigation")
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
        // normalizeCustomerName delegates to :core:pipeline's CustomerNameKey.kt.
        val normalized = requireNotNull(TransformRegistry.apply("normalizeCustomerName", bareName) as? String) {
            "fixture must contain a bare name"
        }
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertTrue("customer hash must use the normalized bare name", expectedHash == fields.customerNameHash)
        val rule = assertOk(generate(envelope, selection)).fragment.getValue("screens").jsonArray.single().jsonObject
        val redact = rule.getValue("redact").jsonArray.last().jsonObject
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
        val productionRedact = productionRules.single { it.id == productionMatch.ruleId }.redact
        val namePaths = nodesByPath(source).filterValues { node ->
            productionRedact.entries.firstOrNull { it.find(node) }?.normalize == RedactNormalize.CUSTOMER_NAME
        }.keys
        val coverageSource = restoreWithObligation(envelope.getValue("payload").jsonObject, source, namePaths, productionRedact)
        // Exactly CaptureWriter's rule-declared redact step, on the same restored plaintext tree.
        val productionEnvelope = productionRedact.apply(coverageSource)
        val draftEnvelope = draftRules.single().redact.apply(coverageSource)
        assertEquals("draft must cover production plaintext and mask paths", emptyList<String>(),
            coverageFailures(productionEnvelope, draftEnvelope))
        if (selection.screenClass == "task:dropoff:navigation") {
            assertTrue("dropoff coverage must actually mask plaintext", maskedSlots(productionEnvelope).isNotEmpty())
            // Regenerate from selections, including removal of the hash field's automatic redact.
            val mutations = selection.redacts.indices.map { index ->
                selection.copy(redacts = selection.redacts.filterIndexed { i, _ -> i != index })
            } + selection.fields.filter { it.field == "customerNameHash" || it.field == "customerAddressHash" }.map { field ->
                selection.copy(fields = selection.fields - field)
            }
            assertEquals("every emitted redact has a selection mutation", rule.getValue("redact").jsonArray.size, mutations.size)
            for ((index, mutation) in mutations.withIndex()) {
                val mutatedRule = assertOk(generate(envelope, mutation)).fragment.getValue("screens").jsonArray.single()
                val mutated = RuleCompiler.compileRules<UiNode>(JsonArray(listOf(mutatedRule)), RuleContext.SCREEN).single()
                assertFalse("removing redact $index must fail plaintext coverage",
                    coverageFailures(productionEnvelope, mutated.redact.apply(coverageSource)).isEmpty())
            }
        }
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
        val envelope = fixture("delivery_summary_expanded")
        val sheet = listOf(0, 0, 0, 0, 0)
        val total = PathRef(sheet + listOf(2, 1, 0, 0, 0, 0, 0, 2, 0, 0, 2))
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
                PathRef(total.path.dropLast(1) + 1)),
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

    /**
     * Pure fixture restoration: replace EVERY corpus mask token in text/desc, retaining surrounding
     * chrome and tree shape. Paths selected by production's customerName-normalized redact receive
     * "Sample Customer"; all other masked slots receive "123 Example Lane". No committed fixture is
     * changed, and no other slot is invented (an empty address line cannot make a mutation pass).
     */
    private fun restoreCorpusPlaintext(
        payload: JsonObject,
        customerNamePaths: Set<List<Int>>,
        nameShapedPaths: Set<List<Int>> = emptySet(),
    ): JsonObject {
        fun restore(node: JsonObject, path: List<Int>): JsonObject = JsonObject(node.mapValues { (key, value) ->
            when {
                key in listOf("text", "desc") && value is JsonPrimitive && value.isString -> JsonPrimitive(
                    MASK_TOKEN.replace(value.content, when (path) {
                        in nameShapedPaths -> "Sample S."
                        in customerNamePaths -> "Sample Customer"
                        else -> "123 Example Lane"
                    }),
                )
                key == "children" && value is JsonArray -> JsonArray(value.mapIndexed { index, child ->
                    if (child is JsonObject) restore(child, path + index) else child
                })
                else -> value
            }
        })
        return restore(payload, emptyList())
    }

    /**
     * Restoration must never LOSE a production masking obligation (Astra, round 3): a slot the corpus masked is a slot
     * production masks on the real frame. A content-dependent production predicate (the id-less first-last-initial name
     * shape) cannot be recognised from the already-masked text, so a slot restored as an address would silently drop
     * out of production's mask set and a draft could omit that redaction and still pass. The restore therefore runs
     * twice: the default restoration first; every corpus-masked slot production then fails to mask is restored again
     * as a first-last-initial name; and the obligation set is ASSERTED equal before any coverage comparison.
     */
    private fun restoreWithObligation(
        payload: JsonObject,
        source: UiNode,
        customerNamePaths: Set<List<Int>>,
        productionRedact: CompiledRedact,
    ): UiNode {
        val corpusMasks = maskedSlots(source)
        var restored = TestResourceLoader.nodeFromElement(restoreCorpusPlaintext(payload, customerNamePaths))
        assertTrue("coverage source must contain no corpus masks", maskedSlots(restored).isEmpty())
        val lost = corpusMasks - maskedSlots(productionRedact.apply(restored))
        if (lost.isNotEmpty()) {
            restored = TestResourceLoader.nodeFromElement(
                restoreCorpusPlaintext(payload, customerNamePaths, nameShapedPaths = lost.map { it.first }.toSet()),
            )
        }
        assertEquals("restoration must preserve every production masking obligation (path, slot)",
            emptySet<Pair<List<Int>, String>>(), corpusMasks - maskedSlots(productionRedact.apply(restored)))
        return restored
    }

    private fun nodesByPath(root: UiNode): Map<List<Int>, UiNode> {
        val nodes = linkedMapOf<List<Int>, UiNode>()
        fun walk(node: UiNode, path: List<Int>) {
            nodes[path] = node
            node.children.forEachIndexed { index, child -> walk(child, path + index) }
        }
        walk(root, emptyList())
        return nodes
    }

    /** Compare exposed tokens, never hash suffixes; even a partial mask cannot hide leftover PII. */
    private fun coverageFailures(production: UiNode, draft: UiNode): List<String> {
        fun plain(text: String?): Set<String> = MASK_TOKEN.replace(text.orEmpty(), " ")
            .split(Regex("""\s+""")).filter { it.isNotEmpty() }.toSet()
        val failures = mutableListOf<String>()
        val productionNodes = nodesByPath(production)
        val draftNodes = nodesByPath(draft)
        if (productionNodes.keys != draftNodes.keys) failures += "node paths differ"
        for ((path, node) in draftNodes) {
            val expected = productionNodes[path]
            if (!plain(expected?.text).containsAll(plain(node.text))) failures += "exposed text at $path"
            if (!plain(expected?.contentDescription).containsAll(plain(node.contentDescription))) failures += "exposed desc at $path"
        }
        val missingMasks = maskedSlots(production) - maskedSlots(draft)
        if (missingMasks.isNotEmpty()) failures += "missing masks at $missingMasks"
        return failures
    }

    @Test
    fun `restoration preserves a name-shape masking obligation the corpus mask hides`() {
        // Astra round 3 probe: an id-less text node carrying a corpus mask whose ORIGINAL was a bare "Brandy S." — the
        // production dropoff rule masks it through its name-shape predicate, which cannot match the masked text, so
        // the default restoration (an address) would drop the obligation. The two-pass restore must re-establish it.
        val envelope = fixture("dropoff_navigation")
        val payload = envelope.getValue("payload").jsonObject
        val probe = Json.parseToJsonElement("""{"class":"android.widget.TextView","isEnabled":true,"text":"[redacted:abcd]",
            "bounds":{"left":0,"top":2300,"right":1080,"bottom":2400}}""").jsonObject
        val probed = JsonObject(payload + ("children" to JsonArray(payload.getValue("children").jsonArray + probe)))
        val source = TestResourceLoader.nodeFromElement(probed)
        val production = Json.parseToJsonElement(File(TestRulesetFactory.rulesDir, "doordash.json").readText())
            .jsonObject.getValue("screens").jsonArray
        val productionRules = RuleCompiler.compileRules<UiNode>(production, RuleContext.SCREEN)
        val match = requireNotNull(Ruleset(productionRules).matchFirst(source, platformWire = "doordash"))
        val productionRedact = productionRules.single { it.id == match.ruleId }.redact
        val namePaths = nodesByPath(source).filterValues { node ->
            productionRedact.entries.firstOrNull { it.find(node) }?.normalize == RedactNormalize.CUSTOMER_NAME
        }.keys
        val probePath = listOf(payload.getValue("children").jsonArray.size)
        assertFalse("the masked probe cannot be recognised as a name slot up front", probePath in namePaths)
        val restored = restoreWithObligation(probed, source, namePaths, productionRedact)
        assertTrue("production must mask the restored probe", (probePath to "text") in maskedSlots(productionRedact.apply(restored)))
    }

    @Test
    fun `coverage restores every mask and detects plaintext left beside a mask`() {
        val payload = Json.parseToJsonElement("""{"class":"TextView","text":"Deliver to [redacted:abcd]",
            "bounds":{"left":0,"top":0,"right":10,"bottom":10},
            "desc":"[redacted] and [redacted:1234]","children":[{"text":"[redacted]",
            "bounds":{"left":0,"top":0,"right":10,"bottom":10}}]}""").jsonObject
        val restored = restoreCorpusPlaintext(payload, setOf(emptyList()))
        assertEquals("Deliver to Sample Customer", restored.getValue("text").jsonPrimitive.content)
        assertEquals("Sample Customer and Sample Customer", restored.getValue("desc").jsonPrimitive.content)
        assertEquals("123 Example Lane", restored.getValue("children").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        val production = TestResourceLoader.nodeFromElement(payload)
        val partial = TestResourceLoader.nodeFromElement(JsonObject(payload + ("text" to JsonPrimitive("Deliver to [redacted] Sample Customer"))))
        assertEquals(maskedSlots(production), maskedSlots(partial))
        assertFalse(coverageFailures(production, partial).isEmpty())
        val fullyMasked = TestResourceLoader.nodeFromElement(JsonObject(payload + ("text" to JsonPrimitive("[redacted]"))))
        assertTrue(coverageFailures(production, fullyMasked).isEmpty())
    }

    private fun maskedSlots(root: UiNode): Set<Pair<List<Int>, String>> {
        val slots = mutableSetOf<Pair<List<Int>, String>>()
        fun walk(node: UiNode, path: List<Int>) {
            if (node.text?.let(MASK_TOKEN::containsMatchIn) == true) slots += path to "text"
            if (node.contentDescription?.let(MASK_TOKEN::containsMatchIn) == true) slots += path to "desc"
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
                        Regex(PiiShapes.FIRST_LAST_INITIAL_EMBEDDED, RegexOption.IGNORE_CASE).containsMatchIn(text))
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

    /** Snapshot folders are pruned by the librarian; these copies are the gate's stable inputs; originals stay in the corpus for recognition tests. */
    private fun fixture(screen: String): JsonObject {
        val file = File("src/test/resources/census-drafts/doordash/$screen.json")
        val envelope = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("uinode.v1", envelope.getValue("schemaId").jsonPrimitive.content)
        return envelope
    }

    private fun ref(envelope: JsonObject, path: List<Int>, id: String): PathRef {
        val node = requireNotNull(EnvelopeWalk.walk(envelope.getValue("payload").jsonObject).at(path))
        assertEquals("view id at $path", id, node.idSuffix)
        return PathRef(path)
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
        val MASK_TOKEN = Regex("""\[redacted(?::[0-9a-f]{4})?]""", RegexOption.IGNORE_CASE)
    }
}
