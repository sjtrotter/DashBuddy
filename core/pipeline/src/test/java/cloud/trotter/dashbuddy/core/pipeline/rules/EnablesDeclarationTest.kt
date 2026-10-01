package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The required action declaration must match all screen-rule binding targets at load time (#1167). */
class EnablesDeclarationTest {

    private fun compile(json: String): List<CompiledRule<UiNode>> =
        RuleCompiler.compileRules(Json.parseToJsonElement(json).jsonArray, RuleContext.SCREEN)

    private fun rule(fields: String = "") = """
    [{
      "id": "doordash.screen.offer_popup_test",
      "priority": 10,
      $fields
      "require": { "exists": { "hasText": "Accept" } }
    }]
    """.trimIndent()

    private fun assertRejected(json: String) {
        try {
            compile(json)
            fail("expected RuleCompileException for invalid 'enables'")
        } catch (e: RuleCompileException) {
            assertTrue("error must name 'enables': ${e.message}", e.message!!.contains("'enables'"))
        }
    }

    /** A bound action cannot silently omit its consent declaration (#1167). */
    @Test
    fun `binding acceptButton without enables is rejected`() {
        assertRejected(rule(""""bind": { "acceptButton": { "find": { "hasText": "Accept" } } },"""))
    }

    /** A declaration cannot advertise an action for which the rule has no target (#1167). */
    @Test
    fun `enables without a matching binding is rejected`() {
        assertRejected(rule(""""enables": ["accept_offer"],"""))
    }

    /** Repeated action wires are rejected before set comparison can hide duplicates (#1167). */
    @Test
    fun `duplicate enables are rejected`() {
        assertRejected(rule("""
            "enables": ["accept_offer", "accept_offer"],
            "bind": { "acceptButton": { "find": { "hasText": "Accept" } } },
        """.trimIndent()))
    }

    /** Only app-owned action wires can appear in the declaration (#1167). */
    @Test
    fun `unknown action wire is rejected`() {
        assertRejected(rule(""""enables": ["tap_button"],"""))
    }

    /** The declaration must be a JSON array containing only string action wires (#1167). */
    @Test
    fun `enables must be an array of strings`() {
        for (enables in listOf("\"accept_offer\"", "null", "{}", "[1]", "[true]", "[null]", "[{}]")) {
            assertRejected(rule(""""enables": $enables,"""))
        }
    }

    /** Two layouts aimed at the same action need just one declaration (#1167). */
    @Test
    fun `a two-branch accept binding compiles with one declared action`() {
        val rules = compile(rule("""
            "enables": ["accept_offer"],
            "branches": [
              { "bind": { "acceptButton": { "find": { "hasText": "Accept" } } } },
              { "bind": { "acceptButton": { "find": { "hasText": "Add to route" } } } }
            ],
        """.trimIndent()))
        assertEquals(1, rules.size)
        assertEquals(2, rules.single().branches.size)
    }

    /** Rules without action bindings need no enables declaration (#1167). */
    @Test
    fun `a rule with no bindings and no enables compiles`() {
        assertEquals(1, compile(rule()).size)
    }
}
