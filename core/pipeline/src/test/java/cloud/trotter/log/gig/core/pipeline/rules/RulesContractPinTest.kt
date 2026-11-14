package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.action.RuleAction
import cloud.trotter.log.gig.domain.pipeline.RuleEngineConstants
import cloud.trotter.log.gig.domain.pipeline.StateMachineContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * #1568: [RuleEngineConstants.RULES_CONTRACT] is the version of the vocabulary the rule compiler
 * accepts, which a rules feed manifest names. This test fingerprints that vocabulary (the editor
 * schemas without their prose, the load-validated contract tables, the per-file caps) and pins the
 * fingerprint to the contract number, so a vocabulary change cannot ship without a decision.
 *
 * When it fails: if a rule written for the new vocabulary could fail to load on an app built
 * before the change (a new predicate, transform, shape, field, intent or a tighter cap), bump
 * [RuleEngineConstants.RULES_CONTRACT] and pin the printed fingerprint under the new number. If no
 * such rule exists (a loosened cap, a removed unused entry), re-pin it under the current number.
 */
class RulesContractPinTest {

    private val pinned = mapOf(
        1 to "35c4f4599ed98543faec1362e60c1fef867cf6664a4337d3c27e69486b50b75a",
    )

    @Test
    fun `the rules contract number moves with the compiler vocabulary`() {
        val fingerprint = sha256Hex(vocabulary())
        assertEquals(
            "rule vocabulary fingerprint for contract ${RuleEngineConstants.RULES_CONTRACT} " +
                "(see this test's KDoc for what to do)",
            pinned[RuleEngineConstants.RULES_CONTRACT],
            fingerprint,
        )
    }

    private fun vocabulary(): String = buildString {
        for (schema in listOf("docs/rules.schema.json", "docs/rules.fragment.schema.json")) {
            appendLine(canonical(withoutProse(Json.parseToJsonElement(File(repoRoot, schema).readText()))))
        }
        appendLine("format=${RuleEngineConstants.MAX_SUPPORTED_FORMAT_VERSION}")
        appendLine("requiredByShape=${table(ParsedFieldsFactory.REQUIRED_FIELDS_BY_SHAPE)}")
        appendLine("oneOfByShape=${ParsedFieldsFactory.REQUIRED_ONE_OF_BY_SHAPE.toSortedMap().mapValues { (_, groups) -> groups.map { it.sorted() } }}")
        appendLine("customerByShape=${table(ParsedFieldsFactory.CUSTOMER_FIELDS_BY_SHAPE)}")
        appendLine("flows=${StateMachineContract.SUPPORTED_FLOWS.sorted()}")
        appendLine("modes=${StateMachineContract.SUPPORTED_MODES.sorted()}")
        appendLine("idleSurfaces=${StateMachineContract.IDLE_SURFACES.sorted()}")
        appendLine("offerSurfaces=${StateMachineContract.SUPPORTED_OFFER_SURFACES.sorted()}")
        appendLine("declarations=${table(StateMachineContract.PARSE_DECLARATION_LITERALS)}")
        appendLine("verbs=${StateMachineContract.SUPPORTED_VERBS.sorted()}")
        appendLine("triggers=${StateMachineContract.SUPPORTED_TRIGGERS.sorted()}")
        appendLine("requiredByFlow=${table(StateMachineContract.REQUIRED_FIELDS_BY_FLOW)}")
        appendLine("effectIntents=${table(StateMachineContract.EFFECT_INTENTS)}")
        appendLine("actions=${RuleAction.entries.map { it.wire }.sorted()}")
        appendLine(
            "caps=" + listOf(
                MAX_RULE_FILE_BYTES, RuleCompiler.MAX_RULES_PER_FILE, RuleCompiler.MAX_BRANCHES_PER_RULE,
                RuleCompiler.MAX_EFFECTS_PER_RULE, RuleCompiler.MAX_DEPTH, RuleCompiler.MAX_PARSE_DEPTH,
                RuleCompiler.MAX_JSON_DEPTH, RuleCompiler.MAX_REGEX_LENGTH, RegexSafety.MAX_PROGRAM_SIZE,
                RegexSafety.MAX_REPEAT, RegexSafety.MAX_GROUP_DEPTH,
            ),
        )
    }

    private fun table(map: Map<String, Set<String>>) = map.toSortedMap().mapValues { it.value.sorted() }

    /** Annotation keys whose string value is prose: editing them changes no rule's meaning. */
    private val proseKeys = setOf("description", "markdownDescription", "title", "\$comment")

    private fun withoutProse(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.filterNot { (k, v) -> (k in proseKeys && v is JsonPrimitive) || k == "examples" }
                .mapValues { withoutProse(it.value) },
        )
        is JsonArray -> JsonArray(element.map(::withoutProse))
        is JsonPrimitive -> element
    }

    private fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.toSortedMap().entries.joinToString(",", "{", "}") { (k, v) ->
            "${JsonPrimitive(k)}:${canonical(v)}"
        }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> element.toString()
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }

    private val repoRoot: File by lazy {
        var dir = File(".").absoluteFile.normalize()
        while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile ?: error("could not locate the repo root")
        dir
    }
}
