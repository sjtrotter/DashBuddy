package cloud.trotter.dashbuddy.core.pipeline.rules

import android.content.Context
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * #624 (VET V4) — a rule FILE whose own ids collide within a rule type is skipped
 * per the malformed-file-skip policy, NOT compiled with a shadowed byId map (which
 * would let the capture-redaction lookup resolve to the wrong rule). Skipping one
 * file must not blind all sensing (no throw into Ruleset.init).
 *
 * loadSingle touches neither the Context nor the grant store, so both are mocked.
 */
class JsonRuleInterpreterDupIdTest {

    private val interpreter = JsonRuleInterpreter(mock<Context>(), mock<RuleCapabilityGrants>())

    @Test
    fun `a file with duplicate screen ids is skipped`() {
        val json = """
            {
              "format_version": 2,
              "platform_id": "doordash.driver",
              "screens": [
                { "id": "doordash.screen.dup", "priority": 9101, "require": { "exists": { "hasText": "A" } } },
                { "id": "doordash.screen.dup", "priority": 9102, "require": { "exists": { "hasText": "B" } } }
              ]
            }
        """.trimIndent()
        assertNull("duplicate id must skip the whole file", interpreter.loadSingle(json, "dup.json"))
    }

    @Test
    fun `a file with unique ids compiles`() {
        val json = """
            {
              "format_version": 2,
              "platform_id": "doordash.driver",
              "screens": [
                { "id": "doordash.screen.one", "priority": 9101, "require": { "exists": { "hasText": "A" } } },
                { "id": "doordash.screen.two", "priority": 9102, "require": { "exists": { "hasText": "B" } } }
              ]
            }
        """.trimIndent()
        val bundle = interpreter.loadSingle(json, "ok.json")
        assertNotNull("unique ids must compile", bundle)
    }
}
