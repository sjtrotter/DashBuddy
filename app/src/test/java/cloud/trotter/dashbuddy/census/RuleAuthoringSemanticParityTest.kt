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

import cloud.trotter.census.contract.authoring.DraftResult
import cloud.trotter.census.contract.authoring.EnvelopeWalk
import cloud.trotter.census.contract.authoring.FieldAssignment
import cloud.trotter.census.contract.authoring.PathRef
import cloud.trotter.census.contract.authoring.RuleDraft
import cloud.trotter.census.contract.authoring.Selections
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleCompiler
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleContext
import cloud.trotter.dashbuddy.domain.capture.dto.UiNodeDto
import cloud.trotter.dashbuddy.domain.capture.toDto
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import cloud.trotter.census.contract.authoring.RuleAuthoringVocabulary as Vocabulary

/** Executable counterpart to RuleAuthoringVocabularyGuardTest's predicate-name guard (#1207). */
class RuleAuthoringSemanticParityTest {
    private data class Case(
        val kind: String,
        val value: String,
        val variant: String?,
        val matches: Boolean,
        val transform: String? = null,
        val selectedText: String = "selected",
    ) {
        val atom: JsonObject get() = buildJsonObject { put(kind, value) }
    }

    private val base = Selections("idle", "idle", "semantic_parity", 500, anchors = listOf(PathRef(emptyList())))

    private fun equalityCases(kind: String): List<Case> = listOf(
        "ready" to true, "READY" to true, " ready" to false, "ready " to false,
        "ready extra" to false, "extra ready" to false, "extra ready extra" to false,
        "" to false, null to false,
    ).map { (variant, matches) -> Case(kind, "ready", variant, matches) } + listOf(
        Case(kind, " ready ", " READY ", true),
        Case(kind, " ready ", "ready", false),
    )

    private val peerCases: List<Case> = listOf(
        ":id/value" to true, "pkg:id/value" to true, "PKG:ID/VALUE" to true,
        " pkg:id/value" to true, "id/value" to false, "value" to false,
        "pkg:id/value_extra" to false, "pkg:id/extra_value" to false,
        "pkg:id/extra_value_extra" to false, "" to false, null to false,
    ).map { (variant, matches) -> Case("hasIdSuffix", ":id/value", variant, matches) } + listOf(
        "TextView" to true, "android.widget.TextView" to true, "TEXTVIEW" to true,
        " TextView" to true, "CustomTextView" to true, "TextViewExtra" to false,
        "CustomTextViewExtra" to false, "" to false, null to false,
    ).map { (variant, matches) -> Case("hasClassNameEndsWith", "TextView", variant, matches) } +
        equalityCases("hasDesc") + equalityCases("hasPrecedingSiblingText") + listOf(
            "3.8 mi" to true, "3.8 MI" to true, " 3.8 mi" to false, "3.8 mi " to false,
            "3.8 mi extra" to false, "extra 3.8 mi" to false, "extra 3.8 mi extra" to false,
            "3.8 mi\n" to false, "" to false, null to false,
        ).map { (variant, matches) ->
            Case("hasTextMatchesRegex", Vocabulary.VALUE_SHAPES_BY_TRANSFORM.getValue("parseDistance"),
                variant, matches, "parseDistance", "3.8 mi")
        } + listOf(
            "$8.30" to true, "$8.30 " to true, "$8.30 bonus" to true,
            "$8.30 \rbonus" to true, " $8.30" to false, "extra $8.30" to false,
            "$8.30\n" to false, "$8.30 bonus\n" to false,
        ).map { (variant, matches) ->
            Case("hasTextMatchesRegex", Vocabulary.VALUE_SHAPES_BY_TRANSFORM.getValue("parseGlyphCurrency"),
                variant, matches, "parseGlyphCurrency", "$8.30")
        }

    @Test
    fun `compiler matches agree with draft peer decisions`() {
        assertEquals(Vocabulary.EMITTED_NODE_PREDICATES.toSet(), (peerCases.map { it.kind } + "hasText").toSet())
        for (case in peerCases) {
            val probe = probe(case)
            val compiledMatch = RuleCompiler.compileNodePred(case.atom)(probe.candidate)
            assertEquals("compiler $case", case.matches, compiledMatch)
            // Peer helpers are private. A selected field plus one competing node makes their
            // match decision observable as an ambiguity refusal through the public generator.
            val result = generate(probe.tree, base.copy(fields = listOf(probe.field)))
            assertEquals("draft/compiler $case: $result", compiledMatch, result is DraftResult.Refused)
            assertDecision(case, probe, result)
        }
    }

    @Test
    fun `literal anchors retain raw values and obey compiler equality`() {
        // hasText has no peer function: RuleDraft emits the raw anchor literal without counting
        // text peers. Pin that public output and run it on every variant through the real compiler.
        for (kind in listOf("hasText", "hasDesc")) {
            for (case in equalityCases(kind)) {
                fun node(value: String?) = UiNode(className = "TextView",
                    text = value.takeIf { kind == "hasText" },
                    contentDescription = value.takeIf { kind == "hasDesc" })
                val rule = okRule(generate(node(case.value), base))
                val predicate = rule.getValue("require").jsonObject.getValue("exists")
                assertTrue("emitted $case", containsAtom(predicate, case.atom))
                assertEquals("anchor $case", case.matches, RuleCompiler.compileNodePred(predicate)(node(case.variant)))
            }
        }
    }

    @Test
    fun `wire normalization is an explicit exception for trailing id and class whitespace`() {
        // EnvelopeWalk trims IDs/classes before private peer checks; the compiler sees raw strings.
        // A padded competitor conservatively refuses as ambiguous; a padded selection is refused
        // outright, as pinned below. Keep the compiler's test input raw.
        for (case in listOf(
            Case("hasIdSuffix", ":id/value", "pkg:id/value ", false),
            Case("hasClassNameEndsWith", "TextView", "TextView ", false),
        )) {
            val probe = probe(case)
            assertEquals("raw compiler $case", false, RuleCompiler.compileNodePred(case.atom)(probe.candidate))
            val result = generate(probe.tree, base.copy(fields = listOf(probe.field)))
            assertEquals("normalized draft $case", DraftResult.Refused(listOf(probe.error)), result)
        }
    }

    @Test
    fun `selected nodes with padded ids or classes are refused outright`() {
        val selected = PathRef(listOf(0))
        for ((slot, node) in listOf(
            "id" to UiNode(text = "selected", viewIdResourceName = "pkg:id/value ", className = "TextView"),
            "class" to UiNode(text = "selected", viewIdResourceName = "pkg:id/value", className = "TextView "),
        )) {
            val tree = UiNode(className = "Layout", viewIdResourceName = "pkg:id/root", children = listOf(node))
                .restoreParents()
            for (selection in listOf(
                base.copy(anchors = base.anchors + selected),
                base.copy(fields = listOf(FieldAssignment(selected, "zoneName"))),
                base.copy(redacts = listOf(selected)),
            )) {
                assertEquals("padded $slot: $selection",
                    DraftResult.Refused(listOf("whitespace-padded $slot at [0]")), generate(tree, selection))
            }
        }
    }

    @Test
    fun `compiled find uses the authoring walk preorder and refuses a later ambiguous selection`() {
        fun value(text: String, children: List<UiNode> = emptyList()) =
            UiNode(text = text, viewIdResourceName = "pkg:id/value", className = "TextView", children = children)
        val nested = value("nested", listOf(value("deep")))
        val children = listOf(UiNode(className = "Layout", children = listOf(nested)), value("sibling"))
        for (tree in listOf(value("root", children), UiNode(className = "Layout", children = children))) {
            val payload = payload(tree)
            val walked = EnvelopeWalk.walk(payload).filter { it.idSuffix == "value" }
            assertEquals(if (tree.text == "root") listOf("root", "nested", "deep", "sibling")
                else listOf("nested", "deep", "sibling"), walked.map { it.text })
            val selection = base.copy(anchors = listOf(PathRef(walked.first().path)),
                fields = listOf(FieldAssignment(PathRef(walked.last().path), "zoneName")))
            assertEquals(DraftResult.Refused(listOf("ambiguous id for field at ${walked.last().path}")),
                generate(tree, selection))
            // Compile a deliberately ambiguous find to pin the first-match behavior that makes
            // RuleDraft refuse it, including root-before-child and deep-before-next-sibling.
            val rule = buildJsonObject {
                put("id", "example.screen.find_order")
                put("priority", 500)
                put("state", buildJsonObject { put("flow", "idle") })
                put("require", buildJsonObject { put("exists", atom("hasIdSuffix", ":id/value")) })
                put("parse", buildJsonObject {
                    put("as", "idle")
                    put("fields", buildJsonObject {
                        put("zoneName", buildJsonObject {
                            put("find", atom("hasIdSuffix", ":id/value"))
                            put("read", "text")
                        })
                    })
                })
            }
            val compiled = RuleCompiler.compileRules<UiNode>(JsonArray(listOf(rule)), RuleContext.SCREEN).single()
            assertEquals(walked.first().text, compiled.branches.single().parser(tree, emptyMap())["zoneName"])
        }
    }

    private data class Probe(val tree: UiNode, val candidate: UiNode, val field: FieldAssignment, val error: String)

    private fun probe(case: Case): Probe {
        val selected = UiNode(text = case.selectedText, className = "TextView", viewIdResourceName = "pkg:id/value")
        val candidate = when (case.kind) {
            "hasIdSuffix" -> selected.copy(text = "other", viewIdResourceName = case.variant)
            "hasClassNameEndsWith" -> selected.copy(text = "other", className = case.variant)
            "hasTextMatchesRegex" -> selected.copy(text = case.variant)
            "hasDesc" -> UiNode(contentDescription = case.variant, className = "Label")
            "hasPrecedingSiblingText" -> UiNode(text = "other", className = "TextView")
            else -> error("Unhandled predicate ${case.kind}")
        }
        val label = case.kind in listOf("hasDesc", "hasPrecedingSiblingText")
        val competingRow = when (case.kind) {
            "hasDesc" -> listOf(candidate, UiNode(text = "other", className = "TextView"))
            "hasPrecedingSiblingText" -> listOf(UiNode(text = case.variant, className = "Label"), candidate)
            else -> listOf(candidate)
        }
        val selectedRow = if (label) listOf(
            UiNode(className = "Label", text = case.value.takeIf { case.kind == "hasPrecedingSiblingText" },
                contentDescription = case.value.takeIf { case.kind == "hasDesc" }),
            selected.copy(viewIdResourceName = null),
        ) else listOf(selected)
        val rows = listOf(UiNode(className = "Layout", children = competingRow),
            UiNode(className = "Layout", children = selectedRow)) +
            // Force shape disambiguation even when the variant does not match the shape.
            if (case.transform != null) listOf(selected.copy(text = "other")) else emptyList()
        val tree = UiNode(className = "Layout", viewIdResourceName = "pkg:id/root", children = rows).restoreParents()
        val path = listOf(1, if (label) 1 else 0)
        val field = FieldAssignment(PathRef(path), if (case.transform == null) "zoneName" else "sessionPay",
            case.transform?.let { listOf(it) })
        val error = when (case.kind) {
            "hasDesc" -> "ambiguous sibling label at $path"
            "hasPrecedingSiblingText" -> "ambiguous sibling anchor at $path"
            else -> "ambiguous id for field at $path"
        }
        return Probe(tree, candidate, field, error)
    }

    private fun assertDecision(case: Case, probe: Probe, result: DraftResult) {
        if (result is DraftResult.Refused) {
            assertEquals("refusal $case", listOf(probe.error), result.errors)
        } else {
            val field = okRule(result).getValue("parse").jsonObject.getValue("fields").jsonObject
                .getValue(probe.field.field).jsonObject
            assertTrue("emitted $case", containsAtom(field, case.atom))
            val selector = field["find"] ?: field.getValue("siblingOf")
            val matches = probe.tree.findNodes(RuleCompiler.compileNodePred(selector))
            assertEquals("unique compiled selector $case", 1, matches.size)
            val selectedPath = if (case.kind == "hasDesc") listOf(1, 0) else probe.field.node.path
            val selected = selectedPath.fold(probe.tree) { node, index -> node.children[index] }
            assertSame("selected node $case", selected, matches.single())
        }
    }

    private fun generate(tree: UiNode, selection: Selections): DraftResult = RuleDraft.generate(
        buildJsonObject { put("payload", payload(tree)) }, selection, "example", null, "2026-10-08",
    )

    private fun payload(tree: UiNode): JsonObject = Json.encodeToJsonElement(UiNodeDto.serializer(), tree.toDto()).jsonObject

    private fun okRule(result: DraftResult): JsonObject {
        assertTrue("draft must succeed: $result", result is DraftResult.Ok)
        return (result as DraftResult.Ok).fragment.getValue("screens").jsonArray.single().jsonObject
    }

    private fun containsAtom(element: JsonElement, atom: JsonObject): Boolean = element == atom || when (element) {
        is JsonObject -> element.values.any { containsAtom(it, atom) }
        is JsonArray -> element.any { containsAtom(it, atom) }
        else -> false
    }

    private fun atom(kind: String, value: String): JsonObject = buildJsonObject { put(kind, value) }
}
