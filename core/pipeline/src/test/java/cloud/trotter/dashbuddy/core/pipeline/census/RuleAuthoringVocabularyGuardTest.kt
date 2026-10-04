package cloud.trotter.dashbuddy.core.pipeline.census

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
        }
    }

    @Test
    fun `domain enums keep declaration order`() {
        assertEquals(Flow.entries.map { it.wire }, Vocabulary.FLOWS)
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
            // The owner spells DOUBLE reads `double`, not `dbl`. Only the top-level `f` counts.
            val read = Regex("""\bf\.(?:str|dbl|double|int|long|bool)\("([^"]+)"\)""")
                .findAll(body).map { it.groupValues[1] }.toMutableSet()
            // parsedTime reads these two scalar fallbacks through its textKey/millisKey parameters.
            Regex("""\bf\.parsedTime\("[^"]+",\s*"([^"]+)",\s*"([^"]+)"\)""")
                .findAll(body).forEach { read += it.groupValues.drop(1) }
            val exposed = Vocabulary.FIELDS_BY_SHAPE.getValue(shape).map { it.name }.toSet()
            for (field in exposed) assertTrue("$shape exposes unread field $field", field in read)
            val required = Vocabulary.REQUIRED_FIELDS_BY_SHAPE[shape].orEmpty() +
                Vocabulary.REQUIRED_ONE_OF_BY_SHAPE[shape].orEmpty().flatten()
            for (field in required) assertTrue("$shape is missing required field $field", field in exposed)
            assertEquals("$shape scalar inventory (excluding activity)", read - "activity", exposed)
        }
        for (shape in listOf("sensitive", "noise", "none", "timeline", "ratings")) {
            assertTrue("$shape must expose no scalar fields", Vocabulary.FIELDS_BY_SHAPE.getValue(shape).isEmpty())
        }
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
