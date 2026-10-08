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
package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.census.contract.authoring.EnvelopeWalk
import cloud.trotter.census.contract.authoring.FieldType
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.TreeLimits
import cloud.trotter.dashbuddy.domain.privacy.PiiShapes
import cloud.trotter.dashbuddy.domain.pipeline.StateMachineContract
import cloud.trotter.dashbuddy.domain.state.SessionType
import kotlinx.serialization.json.JsonObject
import cloud.trotter.census.contract.authoring.RuleAuthoringVocabulary as Vocabulary
import cloud.trotter.dashbuddy.core.pipeline.rules.RegexSafety
import cloud.trotter.dashbuddy.core.pipeline.rules.ParsedFieldsFactory
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferSurface
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins the standalone Apache-2.0 vocabulary to its application-side owners. */
class RuleAuthoringVocabularyGuardTest {
    private val repoRoot = locateRepoRoot()
    private val rulesSource = File(repoRoot, "core/pipeline/src/main/java/cloud/trotter/dashbuddy/core/pipeline/rules")
    private val definitions = Json.parseToJsonElement(File(repoRoot, "docs/rules.schema.json").readText())
        .jsonObject.getValue("\$defs").jsonObject

    @Test
    fun `every value shape compiles through the RE2 seam within the measured program budget`() {
        for ((transform, shape) in Vocabulary.VALUE_SHAPES_BY_TRANSFORM) {
            val size = RegexSafety.compileRegex(shape).programSize()
            assertTrue("$transform measured program size $size", size in 1..1_000)
            val runtime = RegexSafety.compileRegex(shape)
            val local = Regex(shape, RegexOption.IGNORE_CASE)
            for (value in listOf("$1.00 \rbonus", "$8.30\n", "$8.30", "3.8 mi\n", "42 min", "12:30 PM")) {
                assertEquals("$transform JVM/RE2 agreement", local.containsMatchIn(value), runtime.containsMatchIn(value))
            }
        }
    }

    @Test
    fun `domain enums keep declaration order`() {
        assertEquals(Flow.entries.map { it.wire }, Vocabulary.FLOWS)
        // SessionType has no wire property: ParsedFieldsFactory uses valueOf, i.e. enum names.
        assertEquals(SessionType.entries.map { it.name }, Vocabulary.SESSION_TYPES)
        // #1069: the draft tool's identity enum mirrors the contract's declaration table.
        assertEquals(StateMachineContract.SUPPORTED_PRESENTATION_IDENTITIES.toList(), Vocabulary.PRESENTATION_IDENTITIES)
        assertEquals(StateMachineContract.SUPPORTED_QUOTE_BASES.toList(), Vocabulary.QUOTE_BASES)
        assertEquals(setOf("presentationIdentity", "quoteBasis"), StateMachineContract.PARSE_DECLARATION_LITERALS.keys)
        assertEquals(Mode.entries.map { it.wire }, Vocabulary.MODES)
        assertEquals(OfferSurface.entries.map { it.wire }, Vocabulary.OFFER_SURFACES)
        assertEquals(TaskPhase.entries.map { it.name }, Vocabulary.TASK_PHASES)
        assertEquals(TaskSubFlow.entries.map { it.name }, Vocabulary.TASK_SUB_FLOWS)
        assertEquals(Vocabulary.FLOWS + listOf("sensitive", "noise"), Vocabulary.SCREEN_CLASSES)
        assertEquals(RuleAction.entries.associate { it.targetBindName to it.wire }, Vocabulary.BIND_TARGETS)
    }

    @Test
    fun `schema shapes and flows contain exactly the authoring vocabulary`() {
        assertEquals(schemaEnum("asEnum").filterNot { it in listOf("click", "notification") }, Vocabulary.SHAPES)
        // Schema order predates Flow order; #1188 deliberately appends without reordering it.
        assertEquals(schemaEnum("flowEnum").toSet(), Vocabulary.FLOWS.toSet())
        assertEquals(schemaEnum("flowEnum").size, Vocabulary.FLOWS.size)
    }

    @Test
    fun `required field contracts match the factory`() {
        assertEquals(ParsedFieldsFactory.REQUIRED_FIELDS_BY_SHAPE,
            Vocabulary.REQUIRED_FIELDS_BY_SHAPE.mapValues { it.value.toSet() })
        assertEquals(ParsedFieldsFactory.REQUIRED_ONE_OF_BY_SHAPE,
            Vocabulary.REQUIRED_ONE_OF_BY_SHAPE.mapValues { (_, groups) -> groups.map { it.toSet() } })
    }

    @Test
    fun `plain transforms match schema and the actual string dispatch`() {
        val source = File(rulesSource, "TransformRegistry.kt").readText()
        val start = source.indexOf("fun apply(name: String, value: String?): Any?")
        val end = source.indexOf("fun apply(spec: JsonObject, value: String?): Any?", start)
        assertTrue("plain transform dispatch boundaries", start >= 0 && end > start)
        val function = source.substring(start, end)
        val dispatch = function.substringAfter("return when (name) {").substringBefore("else ->")
        val accepted = Regex("""^\s*"([a-zA-Z][a-zA-Z0-9]*)"\s*->""", RegexOption.MULTILINE)
            .findAll(dispatch).map { it.groupValues[1] }.toSet()
        assertTrue("plain transform dispatch must not scan empty", accepted.isNotEmpty())
        assertEquals(accepted, Vocabulary.TRANSFORMS.toSet())
        assertEquals(schemaEnum("plainTransform").toSet(), Vocabulary.TRANSFORMS.toSet())
        val validation = source.substringAfter("private val knownPlainTransforms = setOf(").substringBefore(")")
        assertEquals(Regex("\"([^\"]+)\"").findAll(validation).map { it.groupValues[1] }.toSet(), Vocabulary.TRANSFORMS.toSet())
        val parameterized = source.substringAfter("fun apply(spec: JsonObject, value: String?): Any?")
            .substringBefore("//  Compile-time validation")
        for (name in Vocabulary.EMITTED_PARAMETERIZED_TRANSFORMS) {
            assertTrue("parameterized transform $name missing", Regex("\"$name\"\\s*->").containsMatchIn(parameterized))
        }
    }

    @Test
    fun `terminal result types track registry signatures and factory minute widening`() {
        val source = File(rulesSource, "TransformRegistry.kt").readText()
        val dispatch = source.substringAfter("return when (name) {").substringBefore("else ->")
        val calls = Regex(""""([a-zA-Z][a-zA-Z0-9]*)"\s*->\s*([^\n]+)""")
            .findAll(dispatch).associate { it.groupValues[1] to it.groupValues[2].trim() }
        assertEquals(Vocabulary.TRANSFORMS.toSet(), calls.keys)
        val signatures = Regex("""fun\s+(\w+)\([^\n]*\):\s*(String|Double|Long|Int)\??""")
            .findAll(listOf("TransformRegistry.kt", "Sha256.kt", "CustomerNameKey.kt")
                .joinToString("\n") { File(rulesSource, it).readText() })
            .associate { it.groupValues[1] to FieldType.valueOf(it.groupValues[2].uppercase()) }
        val stringMethods = mapOf("trim" to "value.trim()", "lower" to "value.lowercase(Locale.ROOT)",
            "upper" to "value.uppercase(Locale.ROOT)")
        val numberMethods = mapOf("toDouble" to ("value.toDoubleOrNull()" to FieldType.DOUBLE),
            "toInt" to ("value.toIntOrNull()" to FieldType.INT))
        for ((name, call) in calls) {
            val runtimeType = when (name) {
                in stringMethods -> {
                    assertEquals(stringMethods.getValue(name), call)
                    FieldType.STRING
                }
                in numberMethods -> {
                    assertEquals(numberMethods.getValue(name).first, call)
                    numberMethods.getValue(name).second
                }
                else -> signatures.getValue(call.substringBefore('('))
            }
            // These two runtime Int minute counts intentionally serve LONG factory fields. Pin
            // both the actual signature and the Number -> Long coercion instead of hiding drift.
            val authoringType = if (name in listOf("parseMinutes", "parseTotalMinutes")) {
                assertEquals("$name runtime signature", FieldType.INT, runtimeType)
                FieldType.LONG
            } else runtimeType
            assertEquals(name, authoringType, Vocabulary.TRANSFORM_RESULT_TYPE.getValue(name))
        }
        val factory = File(rulesSource, "ParsedFieldsFactory.kt").readText()
        val longReader = factory.substringAfter("private fun Map<String, Any?>.long(key: String): Long?")
            .substringBefore("private fun")
        assertTrue("factory widens minute counts", "is Number -> v.toLong()" in longReader)
        val strip = source.substringAfter("\"stripPrefixes\" -> {").substringBefore("\"extractBefore\"")
        assertTrue("stripPrefixes returns its String result", "var result: String = value" in strip &&
            Regex("""\bresult\s*}""").containsMatchIn(strip))
        assertEquals(FieldType.STRING, Vocabulary.TRANSFORM_RESULT_TYPE["stripPrefixes"])
        assertEquals((Vocabulary.TRANSFORMS + Vocabulary.EMITTED_PARAMETERIZED_TRANSFORMS).toSet(),
            Vocabulary.TRANSFORM_RESULT_TYPE.keys)
    }

    @Test
    fun `every exposed scalar is read by its factory and every required field is exposed`() {
        val source = File(rulesSource, "ParsedFieldsFactory.kt").readText()
        val functions = mapOf(
            "idle" to "Idle", "task" to "Task", "post_task" to "PostTask",
            "session_ended" to "SessionEnded", "paused" to "Paused", "offer" to "Offer",
        )
        for ((shape, function) in functions) {
            val start = source.indexOf("private fun build$function(")
            assertTrue("$shape factory function missing", start >= 0)
            val end = source.indexOf("private fun ", start + 1).let { if (it < 0) source.length else it }
            val body = source.substring(start, end)
            val types = mapOf("str" to FieldType.STRING, "dbl" to FieldType.DOUBLE,
                "double" to FieldType.DOUBLE, "int" to FieldType.INT, "long" to FieldType.LONG, "bool" to FieldType.BOOLEAN)
            val read = Regex("""\bf\.(str|dbl|double|int|long|bool)\("([^"]+)"\)""")
                .findAll(body).associate { it.groupValues[2] to types.getValue(it.groupValues[1]) }.toMutableMap()
            Regex("""\bf\.parsedTime\("[^"]+",\s*"([^"]+)",\s*"([^"]+)"\)""")
                .findAll(body).forEach {
                    read[it.groupValues[1]] = FieldType.STRING
                    read[it.groupValues[2]] = FieldType.LONG
                }
            for (spec in Vocabulary.FIELDS_BY_SHAPE.getValue(shape)) {
                assertEquals("$shape.${spec.name} type", read[spec.name], spec.type)
            }
            val exposed = Vocabulary.FIELDS_BY_SHAPE.getValue(shape).map { it.name }.toSet()
            for (field in exposed) assertTrue("$shape exposes unread field $field", field in read)
            val required = Vocabulary.REQUIRED_FIELDS_BY_SHAPE[shape].orEmpty() +
                Vocabulary.REQUIRED_ONE_OF_BY_SHAPE[shape].orEmpty().flatten()
            for (field in required) assertTrue("$shape is missing required field $field", field in exposed)
            val computed = if (shape == "offer") setOf("activity", "offerHash") else setOf("activity")
            assertEquals("$shape scalar inventory (excluding computed fields)", read.keys - computed, exposed)
        }
        for (shape in listOf("sensitive", "noise", "none", "timeline", "ratings")) {
            assertTrue("$shape must expose no scalar fields", Vocabulary.FIELDS_BY_SHAPE.getValue(shape).isEmpty())
        }
    }

    @Test
    fun `walk and privacy data mirror their owners`() {
        assertEquals(TreeLimits.MAX_TREE_NODES, EnvelopeWalk.MAX_NODES)
        assertEquals(TreeLimits.MAX_TREE_DEPTH, EnvelopeWalk.MAX_DEPTH)
        assertEquals(PiiShapes.NAME_PREFIXES.toSet(), Vocabulary.ANCHOR_LEAD_INS.toSet())
        assertEquals(PiiShapes.GATED_NAME_PREFIXES.keys.toList(), Vocabulary.ANCHOR_GATED_LEAD_INS)
        assertEquals(PiiShapes.FIRST_LAST_INITIAL_BODY, Vocabulary.FIRST_LAST_INITIAL_BODY)
        assertEquals(PiiShapes.FIRST_LAST_INITIAL_EMBEDDED, Vocabulary.FIRST_LAST_INITIAL_EMBEDDED)
    }

    @Test
    fun `emitted node predicates exist in the compiler dispatch`() {
        val source = File(rulesSource, "PredicateCompiler.kt").readText()
        for (name in Vocabulary.EMITTED_NODE_PREDICATES) {
            assertTrue("node predicate $name missing", Regex("\"$name\"\\s*->").containsMatchIn(source))
        }
        assertEquals(listOf("all", "exists"), Vocabulary.EMITTED_TREE_OPERATORS)
        assertEquals(listOf("find", "siblingOf"), Vocabulary.EMITTED_PARSE_EXPRESSIONS)
    }

    @Test
    fun `generated production flow shape pairs are legal including branches`() {
        assertEquals(Vocabulary.SCREEN_CLASSES.toSet(), Vocabulary.LEGAL_SHAPES_BY_CLASS.keys)
        for (flow in Vocabulary.FLOWS) {
            assertEquals(listOf(Vocabulary.DEFAULT_SHAPE_BY_CLASS.getValue(flow), "none").distinct(), Vocabulary.LEGAL_SHAPES_BY_CLASS[flow])
        }
        for (special in listOf("sensitive", "noise")) assertEquals(listOf(special), Vocabulary.LEGAL_SHAPES_BY_CLASS[special])
        val dir = File(repoRoot, "core/pipeline/build/generated/assets/importMatchersRules/rules")
        val files = requireNotNull(dir.listFiles { f -> f.extension == "json" })
        assertTrue("generated rules must exist", files.isNotEmpty())
        var checked = 0
        fun check(rule: JsonObject, inheritedFlow: String? = null, inheritedShape: String? = null) {
            val flow = (rule["state"] as? JsonObject)?.get("flow")?.jsonPrimitive?.content ?: inheritedFlow
            val shape = (rule["parse"] as? JsonObject)?.get("as")?.jsonPrimitive?.content ?: inheritedShape
            val branches = rule["branches"]
            if (branches != null) branches.jsonArray.forEach { check(it.jsonObject, flow, shape) }
            else if (flow != null && shape != null) {
                checked++
                assertTrue("production pair $flow / $shape", shape in Vocabulary.LEGAL_SHAPES_BY_CLASS[flow].orEmpty())
            }
        }
        for (file in files) {
            val rules = Json.parseToJsonElement(file.readText()).jsonObject
            rules["screens"]?.jsonArray?.forEach { check(it.jsonObject) }
        }
        assertTrue("production flow scan must not be empty", checked > 0)
    }

    private fun schemaEnum(name: String): List<String> = definitions.getValue(name).jsonObject
        .getValue("enum").jsonArray.map { it.jsonPrimitive.content }

    private fun locateRepoRoot(): File {
        var dir = File(".").absoluteFile.normalize()
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: error("Could not locate repo root (settings.gradle.kts)")
        }
    }
}
