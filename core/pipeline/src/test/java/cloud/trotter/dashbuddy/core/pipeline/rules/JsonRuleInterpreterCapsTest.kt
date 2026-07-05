package cloud.trotter.dashbuddy.core.pipeline.rules

import android.content.Context
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * #419 (1) — load-time size caps. A single platform file that exceeds
 * [RuleCompiler.MAX_RULES_PER_FILE] / [RuleCompiler.MAX_BRANCHES_PER_RULE] /
 * [RuleCompiler.MAX_EFFECTS_PER_RULE] is rejected as a typed [RuleCompileException]
 * and skipped WHOLE (the platform loads NOTHING, fail closed behind the #432 gate)
 * rather than admitting a DoS bundle. A file at a cap boundary still loads.
 *
 * loadSingle touches neither the Context nor the grant store, so both are mocked
 * (same pattern as [JsonRuleInterpreterDupIdTest]).
 */
class JsonRuleInterpreterCapsTest {

    private val interpreter = JsonRuleInterpreter(mock<Context>(), mock<RuleCapabilityGrants>())

    private fun fileWithScreens(count: Int): String {
        val screens = (0 until count).joinToString(",") { i ->
            """{ "id": "doordash.screen.r$i", "priority": $i, "require": { "exists": { "hasText": "x$i" } } }"""
        }
        return """
            { "format_version": 2, "platform_id": "doordash.driver", "screens": [ $screens ] }
        """.trimIndent()
    }

    @Test
    fun `a file over MAX_RULES_PER_FILE loads nothing`() {
        val json = fileWithScreens(RuleCompiler.MAX_RULES_PER_FILE + 1)
        assertNull("over-cap rule count must skip the whole file", interpreter.loadSingle(json, "toobig.json"))
    }

    @Test
    fun `a file at MAX_RULES_PER_FILE loads fine`() {
        val json = fileWithScreens(RuleCompiler.MAX_RULES_PER_FILE)
        val bundle = interpreter.loadSingle(json, "atcap.json")
        assertNotNull("a file at the rule cap must still compile", bundle)
    }

    @Test
    fun `a rule over MAX_BRANCHES_PER_RULE loads nothing`() {
        val branches = (0..RuleCompiler.MAX_BRANCHES_PER_RULE).joinToString(",") {
            """{ "require": { "exists": { "hasText": "b$it" } } }"""
        }
        val json = """
            { "format_version": 2, "platform_id": "doordash.driver",
              "screens": [ { "id": "doordash.screen.fat", "priority": 10, "branches": [ $branches ] } ] }
        """.trimIndent()
        assertNull("over-cap branches must skip the whole file", interpreter.loadSingle(json, "fatbranch.json"))
    }

    @Test
    fun `a rule over MAX_EFFECTS_PER_RULE loads nothing`() {
        val effects = (0..RuleCompiler.MAX_EFFECTS_PER_RULE).joinToString(",") {
            """{ "log": { "type": "T$it" } }"""
        }
        val json = """
            { "format_version": 2, "platform_id": "doordash.driver",
              "screens": [ { "id": "doordash.screen.effecty", "priority": 10,
                "require": { "exists": { "hasText": "x" } }, "effects": [ $effects ] } ] }
        """.trimIndent()
        assertNull("over-cap effects must skip the whole file", interpreter.loadSingle(json, "effecty.json"))
    }
}
