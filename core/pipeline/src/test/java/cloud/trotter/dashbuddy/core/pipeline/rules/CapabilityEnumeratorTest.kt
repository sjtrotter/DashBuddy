package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit test for the extracted [CapabilityEnumerator] (audit #11). The
 * end-to-end behaviour through the compiler is covered by
 * `RuleCapabilityEnumerationTest` (via `RuleCompiler.enumerateCapabilities`);
 * this hits the extracted object directly so the security-load-bearing
 * consent-key derivation has its own home — the content-pinned key and the
 * fail-closed dedup invariants are asserted in isolation.
 */
class CapabilityEnumeratorTest {

    private fun compile(rulesJson: String): List<CompiledRule<UiNode>> =
        RuleCompiler.compileRules(Json.parseToJsonElement(rulesJson).jsonArray, RuleContext.SCREEN)

    private fun declineRule(bindPredicate: String = """{ "hasText": "Decline" }""") = """
    [{
      "id": "doordash.screen.offer_popup_test",
      "priority": 10,
      "require": { "exists": { "hasText": "Decline" } },
      "bind": { "declineButton": { "find": $bindPredicate } }
    }]
    """.trimIndent()

    @Test
    fun `binding a well-known target yields one keyed capability attributed to its source`() {
        val caps = CapabilityEnumerator.enumerate(compile(declineRule()), source = "asset:doordash.json")
        assertEquals(1, caps.size)
        val cap = caps.single()
        assertEquals("doordash.screen.offer_popup_test", cap.ruleId)
        assertEquals(RuleAction.DECLINE_OFFER, cap.action)
        assertEquals("declineButton", cap.targetBindName)
        assertEquals("asset:doordash.json", cap.source)
        assertTrue("key must be a non-blank hash", cap.key.isNotBlank())
    }

    @Test
    fun `the consent key is stable across compiles`() {
        val a = CapabilityEnumerator.enumerate(compile(declineRule()), "asset:doordash.json").single()
        val b = CapabilityEnumerator.enumerate(compile(declineRule()), "asset:doordash.json").single()
        assertEquals(a.key, b.key)
    }

    @Test
    fun `repointing the binding predicate changes the key - silent escalation is caught`() {
        val decline = CapabilityEnumerator.enumerate(
            compile(declineRule(bindPredicate = """{ "hasText": "Decline" }""")), "s",
        ).single()
        val accept = CapabilityEnumerator.enumerate(
            compile(declineRule(bindPredicate = """{ "hasText": "Accept" }""")), "s",
        ).single()
        assertNotEquals(decline.key, accept.key)
    }

    @Test
    fun `reordering binding keys does NOT change the consent key`() {
        // canonicalJson recursively sorts object keys, so reordering the bind
        // ENTRY's keys must not change the consent key. A multi-key PREDICATE is
        // now rejected by the compiler (#293 item 1), so the reorder is exercised
        // on the entry's own keys (find + optional).
        fun rule(entry: String) = """
        [{
          "id": "doordash.screen.offer_popup_test",
          "priority": 10,
          "require": { "exists": { "hasText": "Decline" } },
          "bind": { "declineButton": $entry }
        }]
        """.trimIndent()
        val one = CapabilityEnumerator.enumerate(
            compile(rule("""{ "find": { "hasText": "Decline" }, "optional": false }""")), "s",
        ).single()
        val reordered = CapabilityEnumerator.enumerate(
            compile(rule("""{ "optional": false, "find": { "hasText": "Decline" } }""")), "s",
        ).single()
        assertEquals(one.key, reordered.key)
    }

    @Test
    fun `non-action bind names produce no capability`() {
        val rules = compile(
            """
            [{
              "id": "doordash.screen.summary_test",
              "priority": 11,
              "require": { "exists": { "hasText": "Decline" } },
              "bind": { "payField": { "find": { "hasTextContaining": "$" } } }
            }]
            """.trimIndent(),
        )
        assertTrue(CapabilityEnumerator.enumerate(rules, "s").isEmpty())
    }
}
